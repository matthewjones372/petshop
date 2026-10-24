package petshop.app

import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.assert.shouldBeRight
import io.github.matthewjones372.lark.kafka.Topic
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.test.story
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

class KafkaMessagingSpec {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()
    }

    @Test
    fun `over Kafka, an event says where it was written and where it was read, by OpenTelemetry's names`() = story {
        Ended().use { ended ->
            val name = "messaging"
            val registry = mapOf<String, Any>("schema.registry.url" to "mock://$name")
            testApp(kafkaBus(Topic(name), kafka.bootstrap, "projection-$name", registry).subgraph<EventBus>()) {
                    bus: EventBus ->
                val read = ConcurrentLinkedQueue<Attributes>()
                Given("a reader") { bus.consumeFrom { _, where -> read.add(where) }.start(Forks()) }.use { _ ->
                    When("Waffle's arrival is published in a producer span") {
                        ended.tracer.span("publish", SpanKind.PRODUCER) { bus.publish(waffle).shouldBeRight() }
                    }
                    Then("the producer span names the topic, partition, offset, key and message ID") {
                        val sent = ended.named("publish").single().attributes
                        sent.get(Messaging.system) shouldBe "kafka"
                        sent.get(Messaging.operation) shouldBe "send"
                        sent.get(Messaging.destination) shouldBe name
                        sent.get(Messaging.partition) shouldBe "0"
                        sent.get(Messaging.offset) shouldBe 0L
                        sent.get(Messaging.key) shouldBe "4"
                        sent.get(Messaging.id) shouldBe "4"
                    }
                    Then("the reader is told the partition, offset, message ID and its group").eventually(30.seconds) {
                        val where = read.single()
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
