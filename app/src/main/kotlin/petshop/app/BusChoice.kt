package petshop.app

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.closingOf
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.typesafe.Reading
import io.github.matthewjones372.lark.app.typesafe.choosing
import io.github.matthewjones372.lark.kafka.Topic
import io.github.matthewjones372.lark.stream.Hub
import petshop.domain.ShopEvent

/** Set by `petshop.bus.kind` in application.conf, overridden by `BUS`. */
sealed interface BusSettings {

    /** Events are not kept if the process stops. */
    data object InProcess : BusSettings

    data class OnKafka(val bootstrap: String, val topic: String, val group: String, val registry: String) :
        BusSettings
}

/** The branch not taken contributes no node, so the in-process bus opens no Kafka producer. */
fun bus(conf: Config): Module =
    conf.choosing("petshop.bus", Reading::busSettings) { chosen ->
        when (chosen) {
            BusSettings.InProcess -> inProcessBus
            is BusSettings.OnKafka -> onKafka(chosen)
        }
    }

private const val IN_PROCESS = "in-process"
private const val KAFKA = "kafka"

private fun Reading.busSettings(): BusSettings =
    when (kind()) {
        KAFKA -> section(KAFKA) {
            BusSettings.OnKafka(string("bootstrap"), string("topic"), string("group"), string("registry"))
        }
        else -> BusSettings.InProcess
    }

private fun Reading.kind(): String =
    of(IN_PROCESS) {
        getString("kind").also { kind ->
            if (kind != IN_PROCESS && kind != KAFKA) {
                throw ConfigException.BadValue(getValue("kind").origin(), "kind", "'$kind' is not $IN_PROCESS or $KAFKA")
            }
        }
    }

// The hub takes BusSettings, so a section that will not read refuses the start even on a subgraph that only reaches
// the bus.
private val inProcessBus: Module =
    closingOf { _: BusSettings -> Hub<ShopEvent>(HUB_CAPACITY) } +
        singleOf(::HubBus).boundTo<EventBus>()

private const val HUB_CAPACITY = 256

private fun onKafka(on: BusSettings.OnKafka): Module =
    kafkaBus(Topic(on.topic), on.bootstrap, on.group, mapOf("schema.registry.url" to on.registry))
