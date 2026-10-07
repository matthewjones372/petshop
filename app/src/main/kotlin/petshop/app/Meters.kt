package petshop.app

import io.github.matthewjones372.lark.app.Health
import io.github.matthewjones372.lark.app.HealthRegistry
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

/** The probes the shop's graph declares, each reported as its own series so an alert can name the one failing. */
val checks = listOf("shop", "database")

/**
 * What the shop says about itself beyond its own counters: the JVM it runs in, under the names a JVM dashboard
 * expects (`jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `process_cpu_usage`), and its health, so a probe that
 * stops answering is an alert rather than something found by asking `/health`.
 */
class Meters(registry: MeterRegistry, private val health: HealthRegistry) : AutoCloseable {

    // The only binder holding something open: it listens for the JVM's GC notifications.
    private val gc = JvmGcMetrics()

    // Asked on its own thread, and only read by the scrape: a probe waiting on a dead database takes its whole
    // timeout, which is longer than a scrape is given, and a scrape that times out reads as the shop being down.
    private val last = AtomicReference<Health>(Health.Up)
    private val asking = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("petshop-health").factory())

    init {
        listOf(JvmMemoryMetrics(), gc, JvmThreadMetrics(), ClassLoaderMetrics(), ProcessorMetrics(), UptimeMetrics())
            .forEach { it.bindTo(registry) }

        asking.scheduleWithFixedDelay({ last.set(health.readiness()) }, 0, ASK_EVERY_MILLIS, TimeUnit.MILLISECONDS)

        Gauge.builder("petshop.ready") { if (readiness() is Health.Down) 0.0 else 1.0 }
            .description("1 while every critical probe answers, as /health says ready")
            .register(registry)
        checks.forEach { check ->
            Gauge.builder("petshop.health.check") { if (check in failing()) 0.0 else 1.0 }
                .tag("check", check)
                .description("1 while this probe answers")
                .register(registry)
        }
    }

    private fun failing(): List<String> = when (val now = readiness()) {
        is Health.Up -> emptyList()
        is Health.Degraded -> now.failing
        is Health.Down -> now.failing
    }

    private fun readiness(): Health = last.get()

    override fun close() {
        asking.shutdownNow()
        gc.close()
    }
}

/** Once a second, after the last answer: a probe that times out delays the next ask rather than piling them up. */
private const val ASK_EVERY_MILLIS = 1_000L
