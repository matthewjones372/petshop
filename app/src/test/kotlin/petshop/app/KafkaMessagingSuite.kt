package petshop.app

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.junit.SharedSuite
import io.github.matthewjones372.lark.junit.expect
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldNotBeNull
import io.github.matthewjones372.lark.junit.story
import io.github.matthewjones372.lark.kafka.Topic
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.start
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

object KafkaMessagingSuite : SharedSuite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    init {
        test(
            "over Kafka, an event says where it was written and where it was read, by OpenTelemetry's names",
        ) { kafka ->
            story {
                Ended().use { ended ->
                    val name = "messaging"
                    val registry = mapOf<String, Any>("schema.registry.url" to "mock://$name")
                    testApp(kafkaBus(Topic(name), kafka.bootstrap, "projection-$name", registry).subgraph<EventBus>()) {
                            bus: EventBus ->
                        val read = ConcurrentLinkedQueue<Attributes>()
                        Given("a reader") { bus.consumeFrom { _, where -> read.add(where) }.start(Forks()) }.use { _ ->
                            When("Waffle's arrival is published in a producer span") {
                                ended.tracer.span(
                                    "publish",
                                    SpanKind.PRODUCER,
                                ) { expect(bus.publish(waffle).isRight()) }
                            } and Then("the producer span names the topic, partition, offset, key and message ID") {
                                ended.named("publish").singleOrNull().shouldNotBeNull { span ->
                                    val sent = span.attributes
                                    (sent.get(Messaging.system) shouldBe "kafka") and
                                        (sent.get(Messaging.operation) shouldBe "send") and
                                        (sent.get(Messaging.destination) shouldBe name) and
                                        (sent.get(Messaging.partition) shouldBe "0") and
                                        (sent.get(Messaging.offset) shouldBe 0L) and
                                        (sent.get(Messaging.key) shouldBe "4") and
                                        (sent.get(Messaging.id) shouldBe "4")
                                }
                            } and Then("the reader is told the partition, offset, message ID and its group") {
                                waitUntil(
                                    30.seconds,
                                    { it.size == 1 },
                                ) { read.toList() }.singleOrNull().shouldNotBeNull {
                                        where ->
                                    (where.get(Messaging.operation) shouldBe "process") and
                                        (where.get(Messaging.partition) shouldBe "0") and
                                        (where.get(Messaging.offset) shouldBe 0L) and
                                        (where.get(Messaging.id) shouldBe "4") and
                                        (where.get(Messaging.group) shouldBe "projection-$name")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
