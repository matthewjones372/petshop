package petshop.app

import io.github.matthewjones372.surefold.Resource
import io.github.matthewjones372.surefold.app.subgraph
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.junit.SharedSuite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldNotBeNull
import io.github.matthewjones372.surefold.junit.story
import io.github.matthewjones372.surefold.kafka.KafkaBroker
import io.github.matthewjones372.surefold.kafka.Topic
import io.github.matthewjones372.surefold.kafka.kafkaBroker
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.start
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
                            }
                            Then("the producer span names the topic, partition, offset, key and message ID") {
                                ended.named("publish").singleOrNull().shouldNotBeNull { span ->
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
