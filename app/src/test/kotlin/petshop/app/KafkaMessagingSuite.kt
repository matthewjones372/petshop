package petshop.app

import dev.surefold.Resource
import dev.surefold.app.subgraph
import dev.surefold.app.testApp
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.story
import dev.surefold.kafka.KafkaBroker
import dev.surefold.kafka.Topic
import dev.surefold.kafka.kafkaBroker
import dev.surefold.otel.Spans
import dev.surefold.stream.Forks
import dev.surefold.stream.start
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
                Spans().use { spans ->
                    val name = "messaging"
                    val registry = mapOf<String, Any>("schema.registry.url" to "mock://$name")
                    testApp(kafkaBus(Topic(name), kafka.bootstrap, "projection-$name", registry).subgraph<EventBus>()) {
                            bus: EventBus ->
                        val read = ConcurrentLinkedQueue<Attributes>()
                        Given("a reader") { bus.consumeFrom { _, where -> read.add(where) }.start(Forks()) }.use { _ ->
                            When("Waffle's arrival is published in a producer span") {
                                spans.tracer.span(
                                    "publish",
                                    SpanKind.PRODUCER,
                                ) { expect(bus.publish(waffle).isRight()) }
                            }
                            Then("the producer span names the topic, partition, offset, key and message ID") {
                                spans.named("publish").singleOrNull().shouldNotBeNull { span ->
                                    val sent = span.attributes
                                    sent.get(Messaging.system) shouldBe "kafka"
                                    sent.get(Messaging.operation) shouldBe "send"
                                    sent.get(Messaging.destination) shouldBe name
                                    sent.get(Messaging.partition) shouldBe "0"
                                    sent.get(Messaging.offset) shouldBe 0L
                                    sent.get(Messaging.key) shouldBe "4"
                                    sent.get(Messaging.id) shouldBe "4"
                                }
                            }
                            Then("the reader is told the partition, offset, message ID and its group").eventually(
                                30.seconds,
                            ) {
                                read.toList().singleOrNull().shouldNotBeNull { where ->
                                    where.get(Messaging.operation) shouldBe "process"
                                    where.get(Messaging.partition) shouldBe "0"
                                    where.get(Messaging.offset) shouldBe 0L
                                    where.get(Messaging.id) shouldBe "4"
                                    where.get(Messaging.group) shouldBe "projection-$name"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
