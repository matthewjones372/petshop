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
import io.github.matthewjones372.lark.kafka.DecodeError
import io.github.matthewjones372.lark.kafka.Topic
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.start
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import petshop.api.Tally
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.Species
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

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

    private fun bus(name: String, deadLetters: (DecodeError) -> Unit = {}) =
        KafkaBus(Topic(name), kafka.bootstrap, group = "projection-$name", registry(name), deadLetters)

    /** The whole service with its bus on Kafka, and its streams on [backend]'s. No port, no arrivals. */
    private fun shopOnKafka(name: String, backend: String): Module {
        val kafkaBus = singleOf<KafkaBus>({ bus(name) }, { bus -> bus.close() }).boundTo<EventBus>()
        val graph = petshop.overriding(single<petshop.domain.ChipRegistry> { FakeRegistry() }).overriding(kafkaBus)
            .onAFreshDatabase()
        val streams = if (backend == "Forks") graph.overriding(single<StreamBackend> { Forks() }) else graph
        return (streams + onKafka).subgraph<OnKafka>().overridingConfig("petshop.outboxEvery = 50ms")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `an adoption reaches the projection through Kafka, on either backend`(backend: String) {
        val tally = testApp(shopOnKafka("adoptions-$backend", backend)) { app: OnKafka ->
            app.shop.adopt(PetId(1), by = "Ada")
            app.projection.settlesOn { tally -> tally.adopted(Species.Tortoise) == 1 }
        }

        tally.events shouldBe 1
        withClue("the projection's consumer committed what it folded in") {
            kafka.committed("projection-adoptions-$backend", "adoptions-$backend") shouldBe 1L
        }
    }

    @Test
    fun `an event on the wire is the registry's Avro, and reads back as the domain event it was`() {
        val nibbles = PetAdopted(seq = 3, pet = Pet(PetId(1), "Nibbles", Species.Tortoise, adopted = true), by = "Ada")
        bus("wire").use { it.publish(nibbles) }

        val bytes = KafkaConsumer(
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG to "raw",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ),
            StringDeserializer(),
            ByteArrayDeserializer(),
        ).use { raw ->
            raw.subscribe(listOf("wire"))
            generateSequence { raw.poll(Duration.ofMillis(200)) }.take(100).flatMap { it }.first()
        }

        withClue("the key is the event's seq, so a partition keeps one event's copies in order") {
            bytes.key() shouldBe "3"
        }
        val body = ByteBuffer.wrap(bytes.value())
        withClue("Confluent's wire format: a zero magic byte, then the id the registry gave the schema") {
            body.get() shouldBe 0.toByte()
        }
        val registered = MockSchemaRegistry.getClientForScope("wire").getSchemaById(body.int)
        registered shouldBe AvroSchema(shopEventSchema)
        ShopEventDeserializer(registry("wire")).deserialize("wire", bytes.value()) shouldBe nibbles
    }

    @Test
    fun `a record that is not the shop's Avro goes to dead letters, and the projection reads on past it`() {
        KafkaProducer(mapOf<String, Any>("bootstrap.servers" to kafka.bootstrap), StringSerializer(), ByteArraySerializer())
            .use { it.send(ProducerRecord("mixed", "junk", "not avro".toByteArray())).get(30, TimeUnit.SECONDS) }
        val dead = ConcurrentLinkedQueue<DecodeError>()
        val waffle = PetArrived(seq = 1, pet = Pet(PetId(9), "Waffle", Species.Dog))

        val seen = ConcurrentLinkedQueue<Any>()
        bus("mixed", deadLetters = dead::add).use { bus ->
            bus.publish(waffle)
            val running = bus.consume { event -> seen.add(event) }.start(Forks())
            val deadline = System.nanoTime() + 30_000_000_000L
            while (seen.isEmpty()) {
                check(System.nanoTime() < deadline) { "nothing reached the projection" }
                Thread.sleep(20)
            }
            running.close()
        }

        seen.toList() shouldBe listOf(waffle)
        withClue("the unreadable record and the event after it are both committed past") {
            kafka.committed("projection-mixed", "mixed") shouldBe 2L
        }
        dead.single().offset shouldBe 0L
        dead.single().cause.shouldBeInstanceOf<Exception>()
    }
}

private fun Tally.adopted(species: Species): Int = bySpecies.single { it.species == species }.adopted

/** The consumer is behind the shop by a tick and two hops, so a test waits for it rather than sleeping. */
private fun Projection.settlesOn(done: (Tally) -> Boolean): Tally {
    val deadline = System.nanoTime() + 30_000_000_000L
    while (!done(tally())) {
        check(System.nanoTime() < deadline) { "the projection never got there: ${tally()}" }
        Thread.sleep(20)
    }
    return tally()
}
