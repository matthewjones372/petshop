package petshop.app

import io.github.matthewjones372.lark.app.testNode
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.test.story
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** No broker needed: these never send, and a Kafka producer connects on its first send. */
class BusChoiceSpec {

    @Test
    fun `left as application conf has it, the bus is in the process`() = story {
        val graph = Given("the service assembled from application.conf as it ships") { Petshop.module }
        Then("its bus is the in-process hub") { testNode(graph) { bus: EventBus -> bus.shouldBeInstanceOf<HubBus>() } }
    }

    @Test
    fun `kind kafka puts the bus on the broker the section names`() = story {
        val graph = Given("bus.kind = kafka, with a broker, a topic, a group and a registry") {
            Petshop.module.overridingConfig(
                """
                petshop.bus.kind = kafka
                petshop.bus.kafka {
                  bootstrap = "127.0.0.1:9092", topic = orders, group = projection, registry = "mock://choice"
                }
                """,
            )
        }
        Then("its bus is KafkaBus") { testNode(graph) { bus: EventBus -> bus.shouldBeInstanceOf<KafkaBus>() } }
    }

    @Test
    fun `a kind that is neither refuses the start, and says what it was given`() = story {
        val graph = Given("bus.kind = rabbit") { Petshop.module.overridingConfig("petshop.bus.kind = rabbit") }
        val refused = When("the bus is started") {
            shouldThrow<IllegalStateException> { testNode(graph) { _: EventBus -> Unit } }
        }
        Then("the start is refused, naming the value and the two it could have been") {
            refused.message shouldContain "'rabbit' is not in-process or kafka"
        }
    }
}
