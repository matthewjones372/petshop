package petshop.app

import io.github.matthewjones372.pelican.health.health
import io.github.matthewjones372.pelican.jetty.PelicanServer
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldBeError
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.github.matthewjones372.pelican.test.wiremock.PelicanWireMock
import io.github.matthewjones372.surefold.Resource
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.overriding
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.app.typesafe.overridingConfig
import io.github.matthewjones372.surefold.app.use
import io.github.matthewjones372.surefold.junit.SharedSuite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeRight
import io.github.matthewjones372.surefold.junit.story
import org.apache.kafka.common.serialization.LongDeserializer
import petshop.api.ShopCodecs
import petshop.api.adoptPet
import petshop.api.getPet
import petshop.api.stats
import petshop.api.toDto
import petshop.domain.AlreadyAdopted
import petshop.domain.NoSuchPet
import petshop.domain.NotChipped
import petshop.domain.PetAdopted
import petshop.domain.Species
import petshop.registry.ChipRecord
import petshop.registry.Problem
import petshop.registry.lookupChip
import petshop.registry.noSuchChip
import petshop.registry.recordKeeper
import kotlin.time.Duration.Companion.seconds

private const val TOPIC = "shop-events-e2e"
private const val GROUP = "projection-e2e"
private const val SCHEMAS = "mock://e2e"
private val schemas = mapOf<String, Any>("schema.registry.url" to SCHEMAS)

private fun chipRegistry(): PelicanWireMock = PelicanWireMock(KotlinxCodecs).apply {
    stub(lookupChip) { petId -> ok(ChipRecord("98100000000000$petId", keeper = "Petshop")) }
    stub(recordKeeper) { (number, keeper) -> ok(ChipRecord(number, keeper.keeper)) }
}

private fun theService(kafka: KafkaBroker, registry: PelicanWireMock, database: DatabaseSettings): Module =
    Petshop.module.overriding(single<RegistrySettings> { RegistrySettings(registry.baseUrl, 2.seconds) })
        .onDatabase(database)
        .overridingConfig(
            """
            petshop.bus {
              kind = kafka
              kafka { bootstrap = "${kafka.bootstrap}", topic = $TOPIC, group = $GROUP, registry = "$SCHEMAS" }
            }
            petshop.port = 0
            petshop.arrivalsEvery = 1h
            petshop.outboxEvery = 20ms
            """,
        )

/** Arrivals are an hour apart, so no new pet lands between reading the table and reading /stats. */
object EndToEndSuite : SharedSuite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    init {
        test("somebody adopts a tortoise, and every part of the service hears about it") { kafka ->
            chipRegistry().use { registry ->
                val database = TestPostgres.fresh()
                story {
                    Given("a chip registry that knows every pet but Mrs Peel") {
                        registry.stub(lookupChip, 3L) answers noSuchChip(Problem("never chipped"))
                    }

                    theService(kafka, registry, database).use { server: PelicanServer ->
                        apiClient(server.baseUrl, ShopCodecs).use { shop ->
                            // The probe's path is Pelican's whatever checks are declared, so an empty `health { }`
                            // names it.
                            val started = Given("the whole service, started as main starts it") {
                                shop.call(health { }.ready, Unit).status shouldBe "pass"
                            }

                            val nibbles = When("Ada adopts Nibbles") { shop.outcome(adoptPet, 1L) }
                            val hers = Then("Nibbles is hers") { nibbles.shouldBeOk().adopted shouldBe true }

                            val again = When("somebody else asks for Nibbles too") { shop.outcome(adoptPet, 1L) }
                            val taken = Then("they are told she is taken") {
                                again.shouldBeError() shouldBe AlreadyAdopted(1).toDto()
                            }

                            val nobody = When(
                                "somebody asks for a pet the shop never had",
                            ) { shop.outcome(adoptPet, 999L) }
                            val none = Then(
                                "there is no such pet",
                            ) { nobody.shouldBeError() shouldBe NoSuchPet(999).toDto() }

                            val peel = When("somebody asks for Mrs Peel") { shop.outcome(adoptPet, 3L) }
                            val unchipped = Then("she has no chip on record") {
                                peel.shouldBeError() shouldBe NotChipped(3).toDto()
                            } and And("she is still in the shop") {
                                shop.outcome(getPet, 3L).shouldBeOk().adopted shouldBe false
                            }

                            val recorded = Then(
                                "the registry recorded one new keeper, for the one adoption that happened",
                            ) {
                                registry.calls(recordKeeper) shouldBe 1
                            } and And("the outbox drains") {
                                waitUntil(5.seconds, { it == 0L }) { database.unsent() } shouldBe 0L
                            } and And("/stats counts every event the table recorded") {
                                val (events, table) = waitUntil(5.seconds, { (events, table) -> events == table }) {
                                    shop.call(stats, Unit).events.toLong() to database.recorded()
                                }
                                events shouldBe table
                            } and And("the tortoise's adoption among them, counted once") {
                                val tally = shop.call(stats, Unit)
                                (tally.bySpecies.single { it.species == Species.Tortoise }.adopted shouldBe 1) and
                                    (tally.duplicates shouldBe 0)
                            }

                            val onTheTopic = When("the topic is read as the broker holds it") {
                                kafka.records(TOPIC, LongDeserializer(), ShopEventDeserializer(schemas))
                            }
                            val published = Then("every event the outbox recorded is on it once, keyed by its seq") {
                                onTheTopic.map { it.key() }.sorted() shouldBe (1..database.recorded()).toList()
                            } and And("the adoptions read back from the Avro as Nibbles' alone") {
                                // Mrs Peel's adoption waited on the registry, which refused it, so the bus never heard
                                // it begin.
                                val adoptions = onTheTopic.map { it.value() }.sortedBy { it.seq }.mapNotNull { event ->
                                    when (event) {
                                        is PetAdopted -> "adopted ${event.pet.name}"
                                        else -> null
                                    }
                                }
                                adoptions shouldBe listOf("adopted Nibbles")
                            } and And("the projection committed every event") {
                                val (committed, table) = waitUntil(
                                    5.seconds,
                                    { (committed, table) -> committed == table },
                                ) {
                                    kafka.committed(GROUP, TOPIC) to database.recorded()
                                }
                                committed shouldBe table
                            }

                            started and hers and taken and none and unchipped and recorded and published
                        }
                    }.shouldBeRight { it }
                }
            }
        }
    }
}
