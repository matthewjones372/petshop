package petshop.app

import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.github.matthewjones372.surefold.Resource
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.junit.SharedSuite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeInstanceOf
import io.github.matthewjones372.surefold.junit.shouldNotBeNull
import io.github.matthewjones372.surefold.junit.story
import io.github.matthewjones372.surefold.kafka.DEAD_LETTER_HEADER
import io.github.matthewjones372.surefold.kafka.DecodeError
import io.github.matthewjones372.surefold.kafka.Topic
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.start
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.LongDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import petshop.domain.Adopted
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.Species
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private fun registry(scope: String): Map<String, Any> = mapOf("schema.registry.url" to "mock://$scope")

private fun KafkaBroker.busOn(name: String, deadLetters: ((DecodeError) -> Unit)? = null): Module =
    kafkaBus(Topic(name), bootstrap, group = "projection-$name", registry(name), deadLetters)

private class KafkaUnderContract(private val kafka: KafkaBroker) : BusUnderContract {

    override fun bus(name: String): Module = kafka.busOn(name)

    override fun busConfig(name: String): String =
        """
        petshop.bus {
          kind = kafka
          kafka { bootstrap = "${kafka.bootstrap}", topic = $name, group = projection-$name, registry = "mock://$name" }
        }
        """

    override val remembersReaders = true

    override fun handled(name: String): Long? = kafka.committed("projection-$name", name)
}

private fun KafkaBroker.junkOn(topic: String) {
    KafkaProducer(
        mapOf<String, Any>("bootstrap.servers" to bootstrap),
        StringSerializer(),
        ByteArraySerializer(),
    )
        .use { it.send(ProducerRecord(topic, "junk", "not avro".toByteArray())).get(30, TimeUnit.SECONDS) }
}

/** The registry is Confluent's in-process `mock://`: real serializers and wire format, no server. */
object KafkaBusSuite : SharedSuite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    init {
        eventBusContract<KafkaBroker>({ name, body -> test(name, body = body) }) { kafka -> KafkaUnderContract(kafka) }

        test("an event on the wire is the registry's Avro, and reads back as the domain event it was") { kafka ->
            story {
                val nibbles = Given("Ada's adoption of Nibbles, as event 3") {
                    PetAdopted(
                        seq = 3,
                        pet = Pet(PetId(1), "Nibbles", Species.Tortoise, standing = Adopted("Ada")),
                        by = "Ada",
                    )
                }
                When("the bus publishes it") { using(kafka.busOn("wire")) { it.publish(nibbles) } }
                val records = waitUntil(30.seconds, { it.size == 1 }) {
                    kafka.records("wire", LongDeserializer(), ByteArrayDeserializer())
                }
                Then("one record is on the topic") {
                    records.singleOrNull().shouldNotBeNull { record ->
                        val body = ByteBuffer.wrap(record.value())
                        And(
                            "its key is the event's seq, as a number, so a partition keeps one event's copies in order",
                        ) { record.key() shouldBe 3L } and
                            And(
                                "it is Confluent's wire format: a zero magic byte, " +
                                    "then the id the registry gave the schema",
                            ) {
                                val magic = body.get()
                                val schema = MockSchemaRegistry.getClientForScope("wire").getSchemaById(body.int)
                                (magic shouldBe 0.toByte()) and (schema shouldBe AvroSchema(shopEventSchema))
                            } and
                            And("it reads back as the event it was") {
                                val deserializer = ShopEventDeserializer(registry("wire"))
                                deserializer.deserialize("wire", record.value()) shouldBe nibbles
                            }
                    }
                }
            }
        }

        test(
            "a record that is not the shop's Avro goes to dead letters, and the projection reads on past it",
        ) { kafka ->
            story {
                Given("a record on the topic that is not the shop's Avro") { kafka.junkOn("mixed") }
                val waffle = And(
                    "Waffle's arrival behind it",
                ) { PetArrived(seq = 1, pet = Pet(PetId(9), "Waffle", Species.Dog)) }
                val dead = ConcurrentLinkedQueue<DecodeError>()
                val seen = ConcurrentLinkedQueue<Any>()

                val reached = using(kafka.busOn("mixed", deadLetters = dead::add)) { bus ->
                    When("the bus publishes Waffle, and a consumer reads the topic") {
                        bus.publish(waffle)
                        bus.consume { event -> seen.add(event) }.start(Forks())
                    }.use { _ ->
                        Then("Waffle reaches the consumer") {
                            waitUntil(30.seconds, { it == listOf(waffle) }) { seen.toList() } shouldBe listOf(waffle)
                        }
                    }
                }
                reached and And("the unreadable record went to dead letters, from offset 0") {
                    dead.singleOrNull().shouldNotBeNull { letter ->
                        (letter.offset shouldBe 0L) and letter.cause.shouldBeInstanceOf<Exception>()
                    }
                } and And("the unreadable record and the event after it are both committed past") {
                    waitUntil(30.seconds, { it == 2L }) { kafka.committed("projection-mixed", "mixed") } shouldBe 2L
                }
            }
        }

        test("by default an unreadable record is written to the dead-letter topic as it was read") { kafka ->
            story {
                Given("a record on the topic that is not the shop's Avro") { kafka.junkOn("lettered") }
                val waffle = PetArrived(seq = 1, pet = Pet(PetId(9), "Waffle", Species.Dog))
                val seen = ConcurrentLinkedQueue<Any>()

                val reached = using(kafka.busOn("lettered")) { bus ->
                    When("the bus publishes Waffle behind it, and a consumer reads the topic") {
                        bus.publish(waffle)
                        bus.consume { event -> seen.add(event) }.start(Forks())
                    }.use { _ ->
                        Then("Waffle reaches the consumer") {
                            waitUntil(30.seconds, { it == listOf(waffle) }) { seen.toList() } shouldBe listOf(waffle)
                        }
                    }
                }
                val letters = waitUntil(30.seconds, { it.size == 1 }) {
                    kafka.records("lettered.dead-letters", StringDeserializer(), ByteArrayDeserializer())
                }
                reached and And("one letter is on the dead-letter topic") {
                    letters.singleOrNull().shouldNotBeNull { letter ->
                        And("it is the record as it was read") {
                            (letter.key() shouldBe "junk") and (String(letter.value()) shouldBe "not avro")
                        } and And("where it came from rides along in its headers") {
                            val header = { name: String ->
                                String(letter.headers().lastHeader("$DEAD_LETTER_HEADER.$name").value())
                            }
                            (header("offset") shouldBe "0") and (header("topic") shouldBe "lettered")
                        }
                    }
                } and And("both records are committed past") {
                    waitUntil(
                        30.seconds,
                        { it == 2L },
                    ) { kafka.committed("projection-lettered", "lettered") } shouldBe 2L
                }
            }
        }
    }
}
