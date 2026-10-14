package petshop.app

import io.github.matthewjones372.pelican.health.Check
import io.github.matthewjones372.pelican.health.Status
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * What the shop says about itself beyond its own counters: the JVM it runs in, under the names a JVM dashboard
 * expects (`jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `process_cpu_usage`), and its readiness, so a check that
 * stops passing is an alert rather than something found by asking `/health/ready`.
 */
class Meters(registry: MeterRegistry, private val readiness: Readiness) : AutoCloseable {

    // The only binder holding something open: it listens for the JVM's GC notifications.
    private val gc = JvmGcMetrics()

    // Asked on its own thread, and only read by the scrape: a check waiting on a dead database takes its whole
    // timeout, which is longer than a scrape is given, and a scrape that times out reads as the shop being down.
    private val failing = AtomicReference<Set<String>>(emptySet())
    private val asking = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("petshop-health").factory())

    init {
        listOf(JvmMemoryMetrics(), gc, JvmThreadMetrics(), ClassLoaderMetrics(), ProcessorMetrics(), UptimeMetrics())
            .forEach { it.bindTo(registry) }

        asking.scheduleWithFixedDelay(::ask, 0, ASK_EVERY_MILLIS, TimeUnit.MILLISECONDS)

        Gauge.builder("petshop.ready") { if (failing.get().isEmpty()) 1.0 else 0.0 }
            .description("1 while every readiness check passes, as /health/ready says pass")
            .register(registry)
        readiness.checks.keys.forEach { check ->
            Gauge.builder("petshop.health.check") { if (check in failing.get()) 0.0 else 1.0 }
                .tag("check", check)
                .description("1 while this check passes")
                .register(registry)
        }
    }

    // A scheduled task that throws is never run again, which would leave the gauges reading their last answer for
    // good. A check that throws is the shop not knowing, which is the same as it failing, as /health/ready has it.
    private fun ask() {
        failing.set(readiness.checks.filterValues { check -> !passes(check) }.keys)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun passes(check: Check): Boolean =
        try {
            check.check() !is Status.Fail
        } catch (_: Exception) {
            false
        }

    override fun close() {
        asking.shutdownNow()
        gc.close()
    }
}

/** Once a second, after the last answer: a check that times out delays the next ask rather than piling them up. */
private const val ASK_EVERY_MILLIS = 1_000L
