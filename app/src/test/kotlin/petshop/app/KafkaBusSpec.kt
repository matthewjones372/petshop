package petshop.app

import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.kafka.DEAD_LETTER_HEADER
import io.github.matthewjones372.lark.kafka.DecodeError
import io.github.matthewjones372.lark.kafka.Topic
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.start
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import petshop.api.Tally
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.Species
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** What a test watches from: the shop to act on, the bus to publish to, the consumer to read. */
private class OnKafka(val shop: PetShop, val bus: EventBus, val projection: Projection)

private val onKafka: Module =
    single { shop: PetShop, bus: EventBus, projection: Projection, _: OutboxRelay -> OnKafka(shop, bus, projection) }

/**
 * The shop's events on Kafka, as Avro in the schema registry's wire format, read back by lark-kafka's
 * consumer loop. The registry is Confluent's in-process mock, `mock://`: the serializers and the wire
 * format are the real ones, and no registry server runs.
 */
class KafkaBusSpec {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()
    }

    private fun registry(scope: String): Map<String, Any> = mapOf("schema.registry.url" to "mock://$scope")

    private fun bus(name: String, deadLetters: ((DecodeError) -> Unit)? = null) =
        KafkaBus(Topic(name), kafka.bootstrap, group = "projection-$name", registry(name), deadLetters)

    /** The whole service with its bus on Kafka. No port, no arrivals. */
    private fun shopOnKafka(name: String): Module {
        val kafkaBus = singleOf<KafkaBus>({ bus(name) }, { bus -> bus.close() }).boundTo<EventBus>()
        val graph = petshop.overriding(single<petshop.domain.ChipRegistry> { FakeRegistry() }).overriding(kafkaBus)
            .onAFreshDatabase()
        return (graph + onKafka).subgraph<OnKafka>().overridingConfig("petshop.outboxEvery = 50ms")
    }

    @Test
    fun `an adoption reaches the projection through Kafka`() = story {
        val shop = Given("the whole service with its bus on Kafka") { shopOnKafka("adoptions") }
        testApp(shop) { app: OnKafka ->
            When("Ada adopts Nibbles") { app.shop.adopt(PetId(1), by = "Ada") }
            val tally = Then("the projection counts a tortoise adopted").eventually(30.seconds) {
                app.projection.tally().also { it.adopted(Species.Tortoise) shouldBe 1 }
            }
            And("it counted one event") { tally.events shouldBe 1 }
            And("its consumer committed what it folded in").eventually(30.seconds) {
                kafka.committed("projection-adoptions", "adoptions") shouldBe 1L
            }
        }
    }

    @Test
    fun `an event on the wire is the registry's Avro, and reads back as the domain event it was`() = story {
        val nibbles = Given("Ada's adoption of Nibbles, as event 3") {
            PetAdopted(seq = 3, pet = Pet(PetId(1), "Nibbles", Species.Tortoise, adopted = true), by = "Ada")
        }
        When("the bus publishes it") { bus("wire").use { it.publish(nibbles) } }
        val record = Then("one record is on the topic").eventually(30.seconds) {
            kafka.records("wire", StringDeserializer(), ByteArrayDeserializer()).single()
        }
        And("its key is the event's seq, so a partition keeps one event's copies in order") { record.key() shouldBe "3" }
        val body = ByteBuffer.wrap(record.value())
        And("it is Confluent's wire format: a zero magic byte, then the id the registry gave the schema") {
            body.get() shouldBe 0.toByte()
            MockSchemaRegistry.getClientForScope("wire").getSchemaById(body.int) shouldBe AvroSchema(shopEventSchema)
        }
        And("it reads back as the event it was") {
            ShopEventDeserializer(registry("wire")).deserialize("wire", record.value()) shouldBe nibbles
        }
    }

    @Test
    fun `a record that is not the shop's Avro goes to dead letters, and the projection reads on past it`() = story {
        Given("a record on the topic that is not the shop's Avro") { junkOn("mixed") }
        val waffle = And("Waffle's arrival behind it") { PetArrived(seq = 1, pet = Pet(PetId(9), "Waffle", Species.Dog)) }
        val dead = ConcurrentLinkedQueue<DecodeError>()
        val seen = ConcurrentLinkedQueue<Any>()

        bus("mixed", deadLetters = dead::add).use { bus ->
            When("the bus publishes Waffle, and a consumer reads the topic") {
                bus.publish(waffle)
                bus.consume { event -> seen.add(event) }.start(Forks())
            }.use { _ ->
                Then("Waffle reaches the consumer").eventually(30.seconds) { seen.toList() shouldBe listOf(waffle) }
            }
        }
        And("the unreadable record went to dead letters, from offset 0") {
            dead.single().offset shouldBe 0L
            dead.single().cause.shouldBeInstanceOf<Exception>()
        }
        And("the unreadable record and the event after it are both committed past").eventually(30.seconds) {
            kafka.committed("projection-mixed", "mixed") shouldBe 2L
        }
    }

    @Test
    fun `by default an unreadable record is written to the dead-letter topic as it was read`() = story {
        Given("a record on the topic that is not the shop's Avro") { junkOn("lettered") }
        val waffle = PetArrived(seq = 1, pet = Pet(PetId(9), "Waffle", Species.Dog))
        val seen = ConcurrentLinkedQueue<Any>()

        bus("lettered").use { bus ->
            When("the bus publishes Waffle behind it, and a consumer reads the topic") {
                bus.publish(waffle)
                bus.consume { event -> seen.add(event) }.start(Forks())
            }.use { _ ->
                Then("Waffle reaches the consumer").eventually(30.seconds) { seen.toList() shouldBe listOf(waffle) }
            }
        }
        val letter = And("one letter is on the dead-letter topic").eventually(30.seconds) {
            kafka.records("lettered.dead-letters", StringDeserializer(), ByteArrayDeserializer()).single()
        }
        And("it is the record as it was read") {
            letter.key() shouldBe "junk"
            String(letter.value()) shouldBe "not avro"
        }
        And("where it came from rides along in its headers") {
            String(letter.headers().lastHeader("$DEAD_LETTER_HEADER.offset").value()) shouldBe "0"
            String(letter.headers().lastHeader("$DEAD_LETTER_HEADER.topic").value()) shouldBe "lettered"
        }
        And("both records are committed past").eventually(30.seconds) {
            kafka.committed("projection-lettered", "lettered") shouldBe 2L
        }
    }

    private fun junkOn(topic: String) {
        KafkaProducer(mapOf<String, Any>("bootstrap.servers" to kafka.bootstrap), StringSerializer(), ByteArraySerializer())
            .use { it.send(ProducerRecord(topic, "junk", "not avro".toByteArray())).get(30, TimeUnit.SECONDS) }
    }
}

private fun Tally.adopted(species: Species): Int = bySpecies.single { it.species == species }.adopted
