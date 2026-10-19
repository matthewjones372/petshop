package petshop.app

import com.typesafe.config.ConfigException
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.choice
import io.github.matthewjones372.lark.app.typesafe.Reading
import io.github.matthewjones372.lark.app.typesafe.config
import io.github.matthewjones372.lark.kafka.Topic

/** Set by `petshop.bus.kind` in application.conf, overridden by `BUS`. */
sealed interface BusSettings {

    /** Events are not kept if the process stops. */
    data object InProcess : BusSettings

    data class OnKafka(val bootstrap: String, val topic: String, val group: String, val registry: String) :
        BusSettings
}

/**
 * The bus `petshop.bus` names. A choice inside the graph: the branch not taken contributes no node, so the in-process
 * bus opens no Kafka producer, and a section that will not read refuses the start through the BusSettings node.
 */
val bus: Module =
    config<BusSettings>("petshop.bus") { busSettings() } +
        choice<BusSettings, EventBus> { chosen ->
            when (chosen) {
                BusSettings.InProcess -> hubBus()
                is BusSettings.OnKafka -> onKafka(chosen)
            }
        }

private const val IN_PROCESS = "in-process"
private const val KAFKA = "kafka"

private fun Reading.busSettings(): BusSettings = when (kind()) {
    KAFKA -> section(KAFKA) {
        BusSettings.OnKafka(string("bootstrap"), string("topic"), string("group"), string("registry"))
    }

    else -> BusSettings.InProcess
}

private fun Reading.kind(): String = of(IN_PROCESS) {
    getString("kind").also { kind ->
        if (kind != IN_PROCESS && kind != KAFKA) {
            throw ConfigException.BadValue(
                getValue("kind").origin(),
                "kind",
                "'$kind' is not $IN_PROCESS or $KAFKA",
            )
        }
    }
}

private fun onKafka(on: BusSettings.OnKafka): Module =
    kafkaBus(Topic(on.topic), on.bootstrap, on.group, mapOf("schema.registry.url" to on.registry))
