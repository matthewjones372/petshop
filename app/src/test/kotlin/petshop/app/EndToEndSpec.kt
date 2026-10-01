package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldBeError
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.github.matthewjones372.pelican.test.wiremock.PelicanWireMockExtension
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import petshop.api.adoptPet
import petshop.api.getPet
import petshop.api.health
import petshop.api.stats
import petshop.domain.AlreadyAdopted
import petshop.domain.NoSuchPet
import petshop.domain.NotChipped
import petshop.domain.Species
import petshop.registry.ChipRecord
import petshop.registry.Problem
import petshop.registry.lookupChip
import petshop.registry.noSuchChip
import petshop.registry.recordKeeper
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The whole service, end to end, in about as many lines as it takes to say what it should do.
 *
 * Three things make it short, one from each library:
 *
 * - **Lark** starts the graph `main` starts — server, actor, arrivals, outbox, relay, bus, projection —
 *   and swaps exactly two nodes: where the registry is, and which database the outbox is in. `use`
 *   gives everything back when the block returns, so there is no teardown to write.
 * - **Pelican** stubs the registry in its own endpoints and calls the shop through its own, so there is
 *   no URL, no status code and no JSON in this file. A failure is the value the endpoint declared.
 * - **Testcontainers** gives the outbox a real Postgres schema of its own. The test holds on to it, so
 *   it can look in the table as well as at the API.
 *
 * The port is 0, so the test never fights the demo, or anything else, for 8080. Arrivals are an hour
 * apart, so no new pet lands between reading the table and reading /stats.
 */
class EndToEndSpec {

    @JvmField
    @RegisterExtension
    val registry = PelicanWireMockExtension(JacksonCodecs).apply {
        stub(lookupChip) { petId -> ok(ChipRecord("98100000000000$petId", keeper = "Petshop")) }
        stub(recordKeeper) { (number, keeper) -> ok(ChipRecord(number, keeper.keeper)) }
    }

    private val database: DatabaseSettings = TestPostgres.fresh()

    private val theService: Module =
        petshop.overriding(single<RegistrySettings> { RegistrySettings(registry.baseUrl, 2.seconds) })
            .onDatabase(database)
            .overridingConfig("petshop.port = 0\npetshop.arrivalsEvery = 1h\npetshop.outboxEvery = 20ms")

    @Test
    fun `somebody adopts a tortoise, and every part of the service hears about it`() {
        registry.stub(lookupChip, 3L) answers noSuchChip(Problem("never chipped"))

        theService.use { server: PelicanServer ->
            apiClient(server.baseUrl, JacksonCodecs).use { shop ->
                shop.call(health, Unit).ready shouldBe true

                shop.outcome(adoptPet, 1L).shouldBeOk().adopted shouldBe true
                shop.outcome(adoptPet, 1L).shouldBeError() shouldBe AlreadyAdopted(1)
                shop.outcome(adoptPet, 999L).shouldBeError() shouldBe NoSuchPet(999)
                shop.outcome(adoptPet, 3L).shouldBeError() shouldBe NotChipped(3)

                withClue("Mrs Peel had no chip, so she is still in the shop") {
                    shop.outcome(getPet, 3L).shouldBeOk().adopted shouldBe false
                }
                withClue("the registry was told about the one adoption that happened, and only that one") {
                    registry.calls(recordKeeper) shouldBe 1
                }
                withClue("the adoption went into the outbox, out through the relay and onto the bus, and /stats read it") {
                    val tortoises = within(5.seconds) {
                        shop.call(stats, Unit).bySpecies.single { it.species == Species.Tortoise }.takeIf { it.adopted > 0 }
                    }
                    tortoises?.adopted shouldBe 1
                }
                withClue("every event the shop wrote to the table left it, and /stats counted each one once") {
                    within(5.seconds) { database.unsent().takeIf { it == 0L } } shouldBe 0L
                    val tally = shop.call(stats, Unit)
                    tally.events.toLong() shouldBe database.recorded()
                    tally.duplicates shouldBe 0
                }
            }
        }.shouldBeRight()
    }
}

/**
 * [read] again until it answers something, or [timeout] passes. The relay and the projection are a
 * tick or two behind the response, so the last check waits for them rather than for a fixed sleep.
 */
private fun <T : Any> within(timeout: Duration, read: () -> T?): T? {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (deadline.hasNotPassedNow()) {
        read()?.let { return it }
        Thread.sleep(20)
    }
    return read()
}
