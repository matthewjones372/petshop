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

/**
 * What the shop says about itself beyond its own counters: the JVM it runs in, under the names a JVM dashboard
 * expects (`jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `process_cpu_usage`), and its readiness, so a check that
 * stops passing is an alert rather than something found by asking `/health/ready`.
 */
class Meters internal constructor(private val gc: JvmGcMetrics, private val asking: Running<Nothing, Unit>) :
    AutoCloseable {

    override fun close() {
        asking.close()
        gc.close()
    }
}

/**
 * The readiness checks are asked on a stream of their own, as the arrivals and the relay run, and only the gauges
 * are read by the scrape: a check waiting on a dead database takes its whole timeout, which is longer than a scrape
 * is given, and a scrape that times out reads as the shop being down. A tick waits for the last answer, so a slow
 * check delays the next ask rather than piling them up.
 */
val meters: Module =
    singleOf(
        { registry: PrometheusMeterRegistry, readiness: Readiness, streams: StreamBackend ->
            // The only binder holding something open: it listens for the JVM's GC notifications.
            val gc = JvmGcMetrics()
            listOf(JvmMemoryMetrics(), gc, JvmThreadMetrics(), ClassLoaderMetrics(), ProcessorMetrics(), UptimeMetrics())
                .forEach { it.bindTo(registry) }

            val asking = Stream.tick(every = 1.seconds, element = Unit)
                .map { _ -> readiness.failing() }
                .runFold(Unit) { _, failing ->
                    gauge("petshop.ready").set(if (failing.isEmpty()) 1.0 else 0.0)
                    // One series per check, so an alert can name the one failing.
                    readiness.checks.keys.forEach { check ->
                        gauge("petshop.health.check", "check" to check).set(if (check in failing) 0.0 else 1.0)
                    }
                }
                .start(streams)
            Meters(gc, asking)
        },
        { meters -> meters.close() },
    )
