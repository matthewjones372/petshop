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
import kotlin.time.Duration.Companion.seconds

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
 * It reads as a story (`Story.kt`, a prototype of Lark specs 0101 and 0102): each step's text is what a
 * failure says, and a step's value is what the next one checks.
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
    fun `somebody adopts a tortoise, and every part of the service hears about it`() = story {
        Given("a chip registry that knows every pet but Mrs Peel") {
            registry.stub(lookupChip, 3L) answers noSuchChip(Problem("never chipped"))
        }

        theService.use { server: PelicanServer ->
            apiClient(server.baseUrl, JacksonCodecs).use { shop ->
                Given("the whole service, started as main starts it") { shop.call(health, Unit).ready shouldBe true }

                val nibbles = When("Ada adopts Nibbles") { shop.outcome(adoptPet, 1L) }
                Then("Nibbles is hers") { nibbles.shouldBeOk().adopted shouldBe true }

                val again = When("somebody else asks for Nibbles too") { shop.outcome(adoptPet, 1L) }
                Then("they are told she is taken") { again.shouldBeError() shouldBe AlreadyAdopted(1) }

                val nobody = When("somebody asks for a pet the shop never had") { shop.outcome(adoptPet, 999L) }
                Then("there is no such pet") { nobody.shouldBeError() shouldBe NoSuchPet(999) }

                val peel = When("somebody asks for Mrs Peel") { shop.outcome(adoptPet, 3L) }
                Then("she has no chip on record") { peel.shouldBeError() shouldBe NotChipped(3) }
                And("she is still in the shop") { shop.outcome(getPet, 3L).shouldBeOk().adopted shouldBe false }

                Then("the registry recorded one new keeper, for the one adoption that happened") {
                    registry.calls(recordKeeper) shouldBe 1
                }
                And("the outbox drains").eventually(5.seconds) { database.unsent() shouldBe 0L }
                And("/stats counts every event the table recorded").eventually(5.seconds) {
                    shop.call(stats, Unit).events.toLong() shouldBe database.recorded()
                }
                And("the tortoise's adoption among them, counted once") {
                    val tally = shop.call(stats, Unit)
                    tally.bySpecies.single { it.species == Species.Tortoise }.adopted shouldBe 1
                    tally.duplicates shouldBe 0
                }
            }
        }.shouldBeRight()
    }
}
