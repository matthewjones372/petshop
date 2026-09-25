package petshop.load

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.junit5.LoadTest
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.report.writeHtmlReport
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import io.github.matthewjones372.proofload.warmingUp
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.extension.RegisterExtension
import petshop.api.adoptPet
import petshop.api.listPets
import petshop.api.stats
import petshop.app.Arrivals
import petshop.app.DatabaseSettings
import petshop.app.Projection
import petshop.app.RegistrySettings
import petshop.app.TestPostgres
import petshop.app.onAFreshDatabase
import petshop.app.onDatabase
import petshop.app.recorded
import petshop.app.unsent
import io.kotest.matchers.longs.shouldBeGreaterThan
import petshop.app.petshop
import petshop.domain.AlreadyAdopted
import petshop.registry.ChipRecord
import petshop.registry.lookupChip
import petshop.registry.recordKeeper
import io.github.matthewjones372.pelican.test.wiremock.PelicanWireMockExtension

/**
 * The whole application under load, started by its own graph in this process: the actor, the arrivals
 * stream and the endpoints, exactly as `main` starts them. Nothing of the shop's is stubbed, and the
 * graph is given back when the block returns. The chip registry is somebody else's service, so it is
 * a WireMock server that knows every chip, and the outbox is in a Postgres the test starts — the two
 * nodes that differ from `main`.
 *
 * The load runs through Pelican's own typed client, so no URL appears in this file at all: a step
 * names the endpoint it calls, and what it expects back is the failure the endpoint declared rather
 * than a status code. A renamed route or a changed error moves the load with it, at compile time.
 */
class PetshopLoadTest {

    /** Every pet has a chip and every keeper is recorded: the registry is not what this measures. */
    @JvmField
    @RegisterExtension
    val registry = PelicanWireMockExtension(JacksonCodecs).apply {
        stub(lookupChip) { petId -> ok(ChipRecord("98100000000000$petId", keeper = "Petshop")) }
        stub(recordKeeper) { (number, keeper) -> ok(ChipRecord(number, keeper.keeper)) }
    }

    /** The shop as `main` starts it, but for the registry: the database is each test's to choose. */
    private val calling: Module =
        petshop.overriding(single<RegistrySettings> { RegistrySettings(registry.baseUrl, 2.seconds) })

    private val theShop: Module = calling.onAFreshDatabase()

    private val browse = step("browse the shop")

    private val adoptTaken = step("adopt a pet somebody already has")

    @LoadTest
    fun `browsing holds up at two hundred a second`(proofload: Proofload) {
        val outcome = theShop.use { server: PelicanServer ->
            apiClient(server.baseUrl, JacksonCodecs).use { client ->
                // `response` rather than `call`: decoding every catalogue into a `List<Pet>` would put
                // the generator's own Jackson time inside a number that is about the shop.
                val shopping = scenario("browsing") {
                    exec(browse) { step ->
                        if (!client.response(listPets, Unit).isSuccess) step.fail("the shop did not answer")
                    }
                }

                // Two seconds of the same load first, recorded nowhere. A cold JVM, connection pool and
                // JIT put every one of a cold run's twenty slowest requests in its first second — p99
                // 453 ms there, 5 to 18 ms in each second after — so without it the p99 below was
                // measuring start-up rather than the shop.
                proofload.run(shopping.at(200.perSecond, over = 10.seconds).warmingUp(2.seconds))
            }
        }

        val result = outcome.shouldBeRight()

        result.writeHtmlReport(Path.of("build/reports/proofload/browsing.html"))
        result.failed shouldBe 0L
        result[browse].responseTime.p99 shouldBeLessThan 100.milliseconds
    }

    /**
     * The claim the actor exists for, checked under load rather than in a unit test: a pet is sold
     * once. The step expects the declared `AlreadyAdopted`, so a single Ok — two people told they got
     * the same tortoise — fails the run, and so does anything the endpoint never declared.
     */
    @LoadTest
    fun `a pet already adopted is never sold again`(proofload: Proofload) {
        val outcome = theShop.use { server: PelicanServer ->
            apiClient(server.baseUrl, JacksonCodecs).use { client ->
                // The first adoption is the setup, and it has to have worked: a rush against a pet
                // nobody took would answer 200s and never reach the claim.
                client.outcome(adoptPet, 1L).shouldBeOk()

                val rush = scenario("the rush") {
                    exec(adoptTaken) { step ->
                        when (val answer = client.outcome(adoptPet, 1L)) {
                            is Outcome.Ok -> step.fail("the tortoise was sold twice")
                            is Outcome.Err ->
                                if (answer.error !is AlreadyAdopted) step.fail("not the declared failure")
                        }
                    }
                }

                proofload.run(rush.at(200.perSecond, over = 10.seconds))
            }
        }

        val result = outcome.shouldBeRight()

        result.writeHtmlReport(Path.of("build/reports/proofload/the-rush.html"))
        withClue("every one of them was told the tortoise was gone") { result.failed shouldBe 0L }
        result[adoptTaken].count shouldBe 2000L
    }

    private val browseEither = step("browse either instance")

    /**
     * Two instances of the service on one outbox table: two actors writing it and two relays claiming
     * from it `FOR UPDATE SKIP LOCKED`, while two hundred a second browse the two of them.
     *
     * Arrivals are asked for every five milliseconds on each rather than every five seconds, so the table
     * is written the whole time and both relays always have something to claim: some seven hundred
     * events over the ten seconds when this was written, split roughly evenly between the two relays. Each instance publishes to
     * a bus of its own, and each bus has a projection of its own, so an event claimed by both relays
     * would be counted once by each projection: the two tallies add up to more than was recorded.
     * They must add up to exactly what was recorded, with the table empty.
     */
    @LoadTest
    fun `two relays on one outbox publish every event once between them`(proofload: Proofload) {
        val database = TestPostgres.fresh()

        val outcome = instance(database, port = 8081).use { first: Instance ->
            instance(database, port = 8082).use { second: Instance ->
                apiClient(first.baseUrl, JacksonCodecs).use { one ->
                    apiClient(second.baseUrl, JacksonCodecs).use { other ->
                        val requests = java.util.concurrent.atomic.AtomicLong()
                        val browsing = scenario("browsing two instances") {
                            exec(browseEither) { step ->
                                val client = if (requests.incrementAndGet() % 2 == 0L) one else other
                                if (!client.response(listPets, Unit).isSuccess) step.fail("an instance did not answer")
                            }
                        }

                        val ran = proofload.run(browsing.at(200.perSecond, over = 10.seconds))

                        // Nothing new is recorded from here, so the relays can finish what is left.
                        first.arrivals.running.close()
                        second.arrivals.running.close()
                        drains(database) { first.projection.tally().events + second.projection.tally().events }
                        val drained = Drained(
                            recorded = database.recorded(),
                            unsent = database.unsent(),
                            first = first.projection.tally().events.toLong(),
                            second = second.projection.tally().events.toLong(),
                        )

                        Triple(ran, drained, one.call(stats, Unit) to other.call(stats, Unit))
                    }
                }
            }
        }

        // Each instance answers whether it started, so there are two to unwrap.
        val (result, drained, served) = outcome.shouldBeRight().shouldBeRight()
        result.writeHtmlReport(Path.of("build/reports/proofload/two-relays.html"))
        result.failed shouldBe 0L

        withClue("every event recorded by either instance was published by exactly one relay") {
            drained.published shouldBe drained.recorded
            drained.unsent shouldBe 0L
        }
        withClue("SKIP LOCKED let both relays claim, rather than one holding everything") {
            drained.first shouldBeGreaterThan 0L
            drained.second shouldBeGreaterThan 0L
        }
        withClue("/stats on each instance says what its own relay published, and nothing twice") {
            val (one, other) = served
            one.events.toLong() shouldBe drained.first
            other.events.toLong() shouldBe drained.second
            one.duplicates shouldBe 0
            other.duplicates shouldBe 0
        }
    }

    /** One instance of the whole service, on [port], with its outbox in [database]. */
    private fun instance(database: DatabaseSettings, port: Int): Module =
        (calling.onDatabase(database) + single { server: PelicanServer, arrivals: Arrivals, projection: Projection ->
            Instance(server.baseUrl, arrivals, projection)
        })
            .overridingConfig("petshop.port = $port\npetshop.arrivalsEvery = 5ms\npetshop.outboxEvery = 20ms")
}

/** What a test of two instances holds of each: where it answers, its arrivals, and its read of its bus. */
private class Instance(val baseUrl: String, val arrivals: Arrivals, val projection: Projection)

/** The outbox once both relays had nothing left to claim, and what each instance's projection read. */
private data class Drained(val recorded: Long, val unsent: Long, val first: Long, val second: Long) {
    val published: Long get() = first + second
}

/**
 * Waits until the table is empty and the projections have caught up with it, or five seconds pass, and
 * leaves the verdict to the assertions. A projection is a tick and a hop behind its relay, so "empty"
 * alone is not "done".
 */
private fun drains(database: DatabaseSettings, published: () -> Int) {
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!(database.unsent() == 0L && published() >= database.recorded()) && System.nanoTime() < deadline) {
        Thread.sleep(20)
    }
}
