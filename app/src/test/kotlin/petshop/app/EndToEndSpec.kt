package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.test.story
import io.github.matthewjones372.pelican.health.health
import io.github.matthewjones372.pelican.jetty.PelicanServer
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldBeError
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.github.matthewjones372.pelican.test.wiremock.PelicanWireMockExtension
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import org.apache.kafka.common.serialization.LongDeserializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import petshop.api.ShopCodecs
import petshop.api.adoptPet
import petshop.api.getPet
import petshop.api.stats
import petshop.api.toDto
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
import kotlin.time.Duration.Companion.seconds

/** Arrivals are an hour apart, so no new pet lands between reading the table and reading /stats. */
class EndToEndSpec {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()

        private const val TOPIC = "shop-events-e2e"
        private const val GROUP = "projection-e2e"
        private const val SCHEMAS = "mock://e2e"
        private val schemas = mapOf<String, Any>("schema.registry.url" to SCHEMAS)
    }

    @JvmField
    @RegisterExtension
    val registry = PelicanWireMockExtension(KotlinxCodecs).apply {
        stub(lookupChip) { petId -> ok(ChipRecord("98100000000000$petId", keeper = "Petshop")) }
        stub(recordKeeper) { (number, keeper) -> ok(ChipRecord(number, keeper.keeper)) }
    }

    private val database: DatabaseSettings = TestPostgres.fresh()

    private val theService: Module =
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

    @Test
    fun `somebody adopts a tortoise, and every part of the service hears about it`() = story {
        Given("a chip registry that knows every pet but Mrs Peel") {
            registry.stub(lookupChip, 3L) answers noSuchChip(Problem("never chipped"))
        }

        theService.use { server: PelicanServer ->
            apiClient(server.baseUrl, ShopCodecs).use { shop ->
                // The probe's path is Pelican's whatever checks are declared, so an empty `health { }` names it.
                Given("the whole service, started as main starts it") {
                    shop.call(health { }.ready, Unit).status shouldBe "pass"
                }

                val nibbles = When("Ada adopts Nibbles") { shop.outcome(adoptPet, 1L) }
                Then("Nibbles is hers") { nibbles.shouldBeOk().adopted shouldBe true }

                val again = When("somebody else asks for Nibbles too") { shop.outcome(adoptPet, 1L) }
                Then("they are told she is taken") { again.shouldBeError() shouldBe AlreadyAdopted(1).toDto() }

                val nobody = When("somebody asks for a pet the shop never had") { shop.outcome(adoptPet, 999L) }
                Then("there is no such pet") { nobody.shouldBeError() shouldBe NoSuchPet(999).toDto() }

                val peel = When("somebody asks for Mrs Peel") { shop.outcome(adoptPet, 3L) }
                Then("she has no chip on record") { peel.shouldBeError() shouldBe NotChipped(3).toDto() }
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

                val onTheTopic = When("the topic is read as the broker holds it") {
                    kafka.records(TOPIC, LongDeserializer(), ShopEventDeserializer(schemas))
                }
                Then("every event the outbox recorded is on it once, keyed by its seq") {
                    onTheTopic.map { it.key() }.sorted() shouldBe (1..database.recorded()).toList()
                }
                And("the adoptions read back from the Avro as Nibbles adopted, then Mrs Peel adopted and returned") {
                    // The actor says yes before the registry says Mrs Peel has no chip, so the undo is an event of its own.
                    val adoptions = onTheTopic.map { it.value() }.sortedBy { it.seq }.mapNotNull { event ->
                        when (event) {
                            is PetAdopted -> "adopted ${event.pet.name}"
                            is PetReturned -> "returned ${event.pet.name}"
                            else -> null
                        }
                    }
                    adoptions shouldBe listOf("adopted Nibbles", "adopted Mrs Peel", "returned Mrs Peel")
                }
                And("the projection committed every event").eventually(5.seconds) {
                    kafka.committed(GROUP, TOPIC) shouldBe database.recorded()
                }
            }
        }.shouldBeRight()
    }
}
