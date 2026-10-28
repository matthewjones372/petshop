package petshop.app

import com.sun.net.httpserver.HttpServer
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.github.matthewjones372.surefold.Resource
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.junit.SharedSuite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeInstanceOf
import io.github.matthewjones372.surefold.junit.shouldBeNull
import io.github.matthewjones372.surefold.junit.shouldHaveSize
import io.github.matthewjones372.surefold.junit.shouldNotBeNull
import io.github.matthewjones372.surefold.junit.story
import io.github.matthewjones372.surefold.kafka.DEAD_LETTER_HEADER
import io.github.matthewjones372.surefold.kafka.DecodeError
import io.github.matthewjones372.surefold.kafka.KafkaBroker
import io.github.matthewjones372.surefold.kafka.Topic
import io.github.matthewjones372.surefold.kafka.kafkaBroker
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.start
import io.github.matthewjones372.surefold.test.eventually
import kotlinx.serialization.json.JsonPrimitive
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
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    override fun handled(name: String): Long? = kafka.committed("projection-$name", Topic(name))
}

/** A schema registry that answers 500 to everything until [up], and then serves the shop's schema for any id. */
private class FlakySchemaRegistry : AutoCloseable {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val serving = AtomicBoolean(false)
    val asked = AtomicInteger()

    init {
        server.createContext("/") { exchange ->
            asked.incrementAndGet()
            val path = exchange.requestURI.path
            val (status, body) = when {
                !serving.get() -> 500 to """{"error_code":50001,"message":"down"}"""

                path.startsWith("/schemas/ids/") -> 200 to """{"schema":${JsonPrimitive(shopEventSchema.toString())}}"""

                // Confluent's 8.x client asks for a subject's associations too; the shop has none.
                path.startsWith("/associations") -> 200 to "[]"

                else -> 404 to """{"error_code":40401,"message":"not here"}"""
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/vnd.schemaregistry.v1+json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    val registry: Map<String, Any> = mapOf("schema.registry.url" to "http://127.0.0.1:${server.address.port}")

    fun up() = serving.set(true)

    override fun close() = server.stop(0)
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
                val records = eventually(30.seconds) {
                    kafka.records(Topic("wire"), LongDeserializer(), ByteArrayDeserializer())
                        .also { it shouldHaveSize 1 }
                }
                Then("one record is on the topic") {
                    records.singleOrNull().shouldNotBeNull { record ->
                        val body = ByteBuffer.wrap(record.value())
                        And(
                            "its key is the event's seq, as a number, so a partition keeps one event's copies in order",
                        ) { record.key() shouldBe 3L }
                        And(
                            "it is Confluent's wire format: a zero magic byte, " +
                                "then the id the registry gave the schema",
                        ) {
                            val magic = body.get()
                            val schema = MockSchemaRegistry.getClientForScope("wire").getSchemaById(body.int)
                            magic shouldBe 0.toByte()
                            schema shouldBe AvroSchema(shopEventSchema)
                        }
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
                Given("a record on the topic that is not the shop's Avro") {
                    val junk = "not avro".toByteArray()
                    kafka.send(Topic("mixed"), "junk", junk, StringSerializer(), ByteArraySerializer())
                }
                val waffle = And(
                    "Waffle's arrival behind it",
                ) { PetArrived(seq = 1, pet = Pet(PetId(9), "Waffle", Species.Dog)) }
                val dead = ConcurrentLinkedQueue<DecodeError>()
                val seen = ConcurrentLinkedQueue<Any>()

                using(kafka.busOn("mixed", deadLetters = dead::add)) { bus ->
                    When("the bus publishes Waffle, and a consumer reads the topic") {
                        bus.publish(waffle)
                        bus.consume { event -> seen.add(event) }.start(Forks())
                    }.use { _ ->
                        Then("Waffle reaches the consumer").eventually(30.seconds) {
                            seen.toList() shouldBe listOf(waffle)
                        }
                    }
                }
                And("the unreadable record went to dead letters, from offset 0") {
                    dead.singleOrNull().shouldNotBeNull { letter ->
                        letter.offset shouldBe 0L
                        letter.cause.shouldBeInstanceOf<Exception>()
                    }
                }
                And("the unreadable record and the event after it are both committed past").eventually(30.seconds) {
                    kafka.committed("projection-mixed", Topic("mixed")) shouldBe 2L
                }
            }
        }

        test("by default an unreadable record is written to the dead-letter topic as it was read") { kafka ->
            story {
                Given("a record on the topic that is not the shop's Avro") {
                    val junk = "not avro".toByteArray()
                    kafka.send(Topic("lettered"), "junk", junk, StringSerializer(), ByteArraySerializer())
                }
                val waffle = PetArrived(seq = 1, pet = Pet(PetId(9), "Waffle", Species.Dog))
                val seen = ConcurrentLinkedQueue<Any>()

                using(kafka.busOn("lettered")) { bus ->
                    When("the bus publishes Waffle behind it, and a consumer reads the topic") {
                        bus.publish(waffle)
                        bus.consume { event -> seen.add(event) }.start(Forks())
                    }.use { _ ->
                        Then("Waffle reaches the consumer").eventually(30.seconds) {
                            seen.toList() shouldBe listOf(waffle)
                        }
                    }
                }
                val letters = eventually(30.seconds) {
                    kafka.records(Topic("lettered.dead-letters"), StringDeserializer(), ByteArrayDeserializer())
                        .also { it shouldHaveSize 1 }
                }
                And("one letter is on the dead-letter topic") {
                    letters.singleOrNull().shouldNotBeNull { letter ->
                        And("it is the record as it was read") {
                            letter.key() shouldBe "junk"
                            String(letter.value()) shouldBe "not avro"
                        }
                        And("where it came from rides along in its headers") {
                            val header = { name: String ->
                                String(letter.headers().lastHeader("$DEAD_LETTER_HEADER.$name").value())
                            }
                            header("offset") shouldBe "0"
                            header("topic") shouldBe "lettered"
                        }
                    }
                }
                And("both records are committed past").eventually(30.seconds) {
                    kafka.committed("projection-lettered", Topic("lettered")) shouldBe 2L
                }
            }
        }

        test("a handler that throws ends the read, and nothing is committed past it") { kafka ->
            story {
                val waffle = Given(
                    "Waffle's arrival",
                ) { PetArrived(seq = 1, pet = Pet(PetId(9), "Waffle", Species.Dog)) }
                val calls = AtomicInteger()

                using(kafka.busOn("throwing")) { bus ->
                    When("the bus publishes Waffle, and a consumer whose handler throws reads the topic") {
                        bus.publish(waffle)
                        bus.consume {
                            calls.incrementAndGet()
                            throw IllegalStateException("a projection bug")
                        }.start(Forks())
                    }.use { running ->
                        Then("the read ends as Died").eventually(30.seconds) {
                            running.exit.toCompletableFuture().getNow(null).shouldBeInstanceOf<Exit.Died>()
                        }
                    }
                }
                And("the handler ran once: the read was not restarted onto the same record") { calls.get() shouldBe 1 }
                And(
                    "nothing was committed",
                ) { kafka.committed("projection-throwing", Topic("throwing")).shouldBeNull() }
            }
        }

        test("a schema registry that is down holds the read back, and it reads on once the registry is back") { kafka ->
            FlakySchemaRegistry().use { flaky ->
                story {
                    val waffle = Given("Waffle's arrival on the topic") {
                        PetArrived(seq = 1, pet = Pet(PetId(9), "Waffle", Species.Dog))
                            .also { arrival -> using(kafka.busOn("outage")) { it.publish(arrival) } }
                    }
                    val seen = ConcurrentLinkedQueue<Any>()

                    using(kafkaBus(Topic("outage"), kafka.bootstrap, "projection-outage", flaky.registry)) { bus ->
                        When("a consumer reads the topic through a registry that answers 500") {
                            bus.consume { event -> seen.add(event) }.start(Forks())
                        }.use { running ->
                            Then(
                                "the registry is asked",
                            ).eventually(30.seconds) { (flaky.asked.get() > 0) shouldBe true }
                            And("nothing has reached the consumer, or been committed") {
                                seen.toList() shouldBe emptyList()
                                kafka.committed("projection-outage", Topic("outage")).shouldBeNull()
                            }
                            When("the registry comes back") { flaky.up() }
                            Then("Waffle reaches the consumer").eventually(30.seconds) {
                                seen.toList() shouldBe listOf(waffle)
                            }
                            And("the read never ended") { running.exit.toCompletableFuture().isDone shouldBe false }
                        }
                    }
                    And("Waffle is committed past").eventually(30.seconds) {
                        kafka.committed("projection-outage", Topic("outage")) shouldBe 1L
                    }
                }
            }
        }
    }
}
