package petshop.app

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.app.testNode
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.PekkoStreams
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * `petshop.bus` picks the bus where the graph is assembled. These look at the nodes that choice leaves
 * and never send anything, so no broker is needed: a producer connects on its first send, not before.
 * EndToEndSpec runs the Kafka branch against a real broker.
 */
class BusChoiceSpec {

    private fun configuredWith(bus: String): Config =
        ConfigFactory.parseString(bus).withFallback(ConfigFactory.load())

    @Test
    fun `left as application conf has it, the bus is in the process`() = story {
        val graph = Given("the service assembled from application.conf as it ships") {
            petshopFrom(ConfigFactory.load())
        }
        Then("its bus is the in-process hub") { testNode(graph) { bus: EventBus -> bus.shouldBeInstanceOf<HubBus>() } }
        And("the projection reads it on Pekko, the only backend that can read a hub") {
            testNode(graph) { streams: ProjectionStreams -> streams.backend.shouldBeInstanceOf<PekkoStreams>() }
        }
    }

    @Test
    fun `kind kafka puts the bus on the broker the section names`() = story {
        val graph = Given("bus.kind = kafka, with a broker, a topic, a group and a registry") {
            petshopFrom(
                configuredWith(
                    """
                    petshop.bus.kind = kafka
                    petshop.bus.kafka {
                      bootstrap = "127.0.0.1:9092", topic = orders, group = projection, registry = "mock://choice"
                    }
                    """,
                ),
            )
        }
        Then("its bus is KafkaBus") { testNode(graph) { bus: EventBus -> bus.shouldBeInstanceOf<KafkaBus>() } }
        And("the projection reads it on Forks, where a blocking poll is one loop on a virtual thread") {
            testNode(graph) { streams: ProjectionStreams -> streams.backend.shouldBeInstanceOf<Forks>() }
        }
    }

    @Test
    fun `a kind that is neither refuses the start, and says what it was given`() = story {
        val graph = Given("bus.kind = rabbit") { petshopFrom(configuredWith("petshop.bus.kind = rabbit")) }
        val refused = When("the bus is started") {
            shouldThrow<IllegalStateException> { testNode(graph) { _: EventBus -> Unit } }
        }
        Then("the start is refused, naming the value and the two it could have been") {
            refused.message shouldContain "'rabbit' is not in-process or kafka"
        }
    }
}
