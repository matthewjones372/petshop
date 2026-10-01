package petshop.app

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.typesafe.Reading
import io.github.matthewjones372.lark.app.typesafe.choosing
import io.github.matthewjones372.lark.kafka.Topic

/** Which bus the shop's events go out on: `petshop.bus.kind` in application.conf, `BUS` in the environment. */
sealed interface BusSettings {

    /** Lark's hub in the same process: nothing to run beside the shop, and nothing kept if it stops. */
    data object InProcess : BusSettings

    /** A Kafka topic, each event Avro in the schema registry's wire format. */
    data class OnKafka(val bootstrap: String, val topic: String, val group: String, val registry: String) :
        BusSettings
}

/**
 * The bus `petshop.bus` names, chosen where the graph is assembled: the branch not taken contributes no
 * node, so a shop on the in-process bus opens no producer and asks no broker anything. Both branches
 * depend on [BusSettings], so a section that will not read refuses the start wherever the bus is reached.
 */
fun bus(conf: Config): Module =
    conf.choosing("petshop.bus", Reading::busSettings) { chosen ->
        when (chosen) {
            BusSettings.InProcess -> inProcessBus
            is BusSettings.OnKafka -> kafkaBus(chosen)
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

/** `kind`, refused as a fault naming where it was set when it is neither bus. */
private fun Reading.kind(): String =
    of(IN_PROCESS) {
        getString("kind").also { kind ->
            if (kind != IN_PROCESS && kind != KAFKA) {
                throw ConfigException.BadValue(getValue("kind").origin(), "kind", "'$kind' is not $IN_PROCESS or $KAFKA")
            }
        }
    }

private val inProcessBus: Module =
    // Closed after the relay stops publishing to it, because the relay depends on it.
    singleOf({ _: BusSettings -> HubBus() }, { bus -> bus.close() }).boundTo<EventBus>()

private fun kafkaBus(on: BusSettings.OnKafka): Module =
    singleOf(
        { _: BusSettings ->
            KafkaBus(Topic(on.topic), on.bootstrap, on.group, mapOf("schema.registry.url" to on.registry))
        },
        { bus -> bus.close() },
    ).boundTo<EventBus>()
