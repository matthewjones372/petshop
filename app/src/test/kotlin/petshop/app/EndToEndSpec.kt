package petshop.app

import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.kafka.Topic
import io.github.matthewjones372.lark.retry
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import petshop.api.adoptPet
import petshop.api.getPet
import petshop.api.health
import petshop.api.stats
import petshop.domain.AlreadyAdopted
import petshop.domain.NoSuchPet
import petshop.domain.NotChipped
import petshop.domain.PetAdopted
import petshop.domain.PetReturned
import petshop.domain.Species
import petshop.registry.ChipRecord
import petshop.registry.Problem
import petshop.registry.lookupChip
import petshop.registry.noSuchChip
import petshop.registry.recordKeeper

/**
 * The whole service, end to end, in about as many lines as it takes to say what it should do.
 *
 * Three things make it short, one from each library:
 *
 * - **Lark** starts the graph `main` starts — server, actor, arrivals, outbox, relay, bus, projection —
 *   and swaps three nodes: where the registry is, which database the outbox is in, and the bus, which is
 *   Kafka here rather than the in-process hub. `use` gives everything back when the block returns, so
 *   there is no teardown to write.
 * - **Pelican** stubs the registry in its own endpoints and calls the shop through its own, so there is
 *   no URL, no status code and no JSON in this file. A failure is the value the endpoint declared.
 * - **Testcontainers** gives the outbox a real Postgres schema of its own, and an embedded broker gives
 *   the bus a real Kafka, with Confluent's in-process `mock://` schema registry. The test holds on to
 *   both, so it can look in the table and on the topic as well as at the API.
 *
 * The port is 0, so the test never fights the demo, or anything else, for 8080. Arrivals are an hour
 * apart, so no new pet lands between reading the table and reading /stats.
 */
class EndToEndSpec {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()

        private const val TOPIC = "shop-events-e2e"
        private const val GROUP = "projection-e2e"
        private val schemas = mapOf<String, Any>("schema.registry.url" to "mock://e2e")
    }

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
            .overriding(
                singleOf<KafkaBus>({ KafkaBus(Topic(TOPIC), kafka.bootstrap, GROUP, schemas) }, { it.close() })
                    .boundTo<EventBus>(),
            )
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
                    patiently.retry {
                        shop.call(stats, Unit).bySpecies.single { it.species == Species.Tortoise }.adopted shouldBe 1
                    }
                }
                withClue("every event the shop wrote to the table left it, and /stats counted each one once") {
                    patiently.retry { database.unsent() shouldBe 0L }
                    // The table empties when the relay publishes; the projection folds a tick or so later.
                    val tally = patiently.retry {
                        shop.call(stats, Unit).also { it.events.toLong() shouldBe database.recorded() }
                    }
                    tally.duplicates shouldBe 0
                }

                val onTheTopic = kafka.records(TOPIC, StringDeserializer(), ShopEventDeserializer(schemas))
                withClue("every event the outbox recorded is on the topic once, keyed by its seq") {
                    onTheTopic.map { it.key() }.sorted() shouldBe (1..database.recorded()).map(Long::toString)
                }
                withClue(
                    "on the wire, as the shop's Avro: Nibbles adopted, and Mrs Peel adopted and then returned, " +
                        "because the actor said yes before the registry said she had no chip",
                ) {
                    val story = onTheTopic.map { it.value() }.sortedBy { it.seq }.mapNotNull { event ->
                        when (event) {
                            is PetAdopted -> "adopted ${event.pet.name}"
                            is PetReturned -> "returned ${event.pet.name}"
                            else -> null
                        }
                    }
                    story shouldBe listOf("adopted Nibbles", "adopted Mrs Peel", "returned Mrs Peel")
                }
                withClue("the projection committed every event it folded in") {
                    patiently.retry { kafka.committed(GROUP, TOPIC) shouldBe database.recorded() }
                }
            }
        }.shouldBeRight()
    }
}

/**
 * Up to 250 more tries, 20 ms apart, rethrowing the last failed assertion when it gives up. The relay and
 * the projection are a tick or two behind the response, so a check of what they did waits for them.
 * Counted rather than timed: Lark's schedules have no bound on elapsed time, so how long giving up takes
 * depends on how long each try does.
 */
private val patiently: Schedule<Throwable, Long> = Schedule.spaced<Throwable>(20.milliseconds) zipLeft Schedule.recurs(250)
