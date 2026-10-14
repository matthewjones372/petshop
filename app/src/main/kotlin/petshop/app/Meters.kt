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

/** The JVM's meters, under the names a JVM dashboard reads. The GC binder is the node: it listens, and needs closing. */
private val jvmMeters: Module =
    singleOf(
        { registry: PrometheusMeterRegistry ->
            JvmGcMetrics().also { gc ->
                listOf(JvmMemoryMetrics(), gc, JvmThreadMetrics(), ClassLoaderMetrics(), ProcessorMetrics(), UptimeMetrics())
                    .forEach { it.bindTo(registry) }
            }
        },
        { gc -> gc.close() },
    )

class ReadinessGauges internal constructor(val running: Running<Nothing, Unit>)

/**
 * Checks run on their own stream, not in the scrape: a check on a dead database outlasts the scrape timeout,
 * and a timed-out scrape reads as the shop being down.
 */
private val readinessGauges: Module =
    singleOf(
        { readiness: Readiness, streams: StreamBackend ->
            Stream.tick(every = 1.seconds, element = Unit)
                .map { _ -> readiness.failing() }
                .runFold(Unit) { _, failing ->
                    // Ready as /health/ready answers it: a stopped background run is a warning there, not a 503.
                    gauge("petshop.ready").set(if (failing.none { it in readiness.critical }) 1.0 else 0.0)
                    readiness.checks.keys.forEach { check ->
                        gauge("petshop.health.check", "check" to check).set(if (check in failing) 0.0 else 1.0)
                    }
                }
                .start(streams)
                .let(::ReadinessGauges)
        },
        { gauges -> gauges.running.close() },
    )

val meters: Module = jvmMeters + readinessGauges
