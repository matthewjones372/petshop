package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.gauge
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.stream.tick
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlin.time.Duration.Companion.seconds

class Meters internal constructor(private val gc: JvmGcMetrics, private val asking: Running<Nothing, Unit>) :
    AutoCloseable {

    override fun close() {
        asking.close()
        gc.close()
    }
}

/**
 * Checks run on their own stream, not in the scrape: a check on a dead database outlasts the scrape timeout,
 * and a timed-out scrape reads as the shop being down.
 */
val meters: Module =
    singleOf(
        { registry: PrometheusMeterRegistry, readiness: Readiness, streams: StreamBackend ->
            // The only binder that needs closing: it listens for GC notifications.
            val gc = JvmGcMetrics()
            listOf(JvmMemoryMetrics(), gc, JvmThreadMetrics(), ClassLoaderMetrics(), ProcessorMetrics(), UptimeMetrics())
                .forEach { it.bindTo(registry) }

            val asking = Stream.tick(every = 1.seconds, element = Unit)
                .map { _ -> readiness.failing() }
                .runFold(Unit) { _, failing ->
                    // Ready as /health/ready answers it: a stopped background run is a warning there, not a 503.
                    gauge("petshop.ready").set(if (failing.none { it in readiness.critical }) 1.0 else 0.0)
                    readiness.checks.keys.forEach { check ->
                        gauge("petshop.health.check", "check" to check).set(if (check in failing) 0.0 else 1.0)
                    }
                }
                .start(streams)
            Meters(gc, asking)
        },
        { meters -> meters.close() },
    )
