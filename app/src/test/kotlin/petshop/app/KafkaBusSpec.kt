package petshop.app

import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.kafka.DEAD_LETTER_HEADER
import io.github.matthewjones372.lark.kafka.DecodeError
import io.github.matthewjones372.lark.kafka.Topic
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.test.story
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.LongDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.Species

/** What a test watches from: the shop to act on, the bus to publish to, the consumer to read. */
/**
 * The shop's events on Kafka, as Avro in the schema registry's wire format, read back by lark-kafka's
 * consumer loop. What every bus promises is [EventBusContract]'s; what is here is Kafka's own. The registry
 * is Confluent's in-process mock, `mock://`: the serializers and the wire format are the real ones, and no
 * registry server runs.
 */
class KafkaBusSpec : EventBusContract() {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()
    }

    override fun bus(name: String): EventBus = kafkaBus(name)

    override val remembersReaders = true

    override fun handled(name: String): Long? = kafka.committed("projection-$name", name)

    private fun registry(scope: String): Map<String, Any> = mapOf("schema.registry.url" to "mock://$scope")

    private fun kafkaBus(name: String, deadLetters: ((DecodeError) -> Unit)? = null) =
        KafkaBus(Topic(name), kafka.bootstrap, group = "projection-$name", registry(name), deadLetters)

    @Test
    fun `an event on the wire is the registry's Avro, and reads back as the domain event it was`() = story {
        val nibbles = Given("Ada's adoption of Nibbles, as event 3") {
            PetAdopted(seq = 3, pet = Pet(PetId(1), "Nibbles", Species.Tortoise, adopted = true), by = "Ada")
        }
        When("the bus publishes it") { kafkaBus("wire").use { it.publish(nibbles) } }
        val record = Then("one record is on the topic").eventually(30.seconds) {
            kafka.records("wire", LongDeserializer(), ByteArrayDeserializer()).single()
        }
        And("its key is the event's seq, as a number, so a partition keeps one event's copies in order") { record.key() shouldBe 3L }
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

        kafkaBus("mixed", deadLetters = dead::add).use { bus ->
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

        kafkaBus("lettered").use { bus ->
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
