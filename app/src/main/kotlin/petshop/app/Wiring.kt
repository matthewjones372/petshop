package petshop.app

import io.github.matthewjones372.lark.app.LarkApp
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.closingOf
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.typesafe.Layered
import io.github.matthewjones372.lark.app.typesafe.Secrets
import io.github.matthewjones372.lark.app.typesafe.config
import io.github.matthewjones372.lark.app.typesafe.configOf
import io.github.matthewjones372.lark.app.typesafe.layeredConfig
import io.github.matthewjones372.lark.app.typesafe.layeredConfigOf
import io.github.matthewjones372.lark.app.typesafe.origins
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.health.Health
import io.micrometer.core.instrument.Metrics as MicrometerRegistries
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import kotlin.time.Duration
import petshop.api.Binders
import petshop.api.petshopApi
import petshop.domain.PetShop

/** [host] is loopback unless something outside this machine has to reach the port. */
data class Settings(val host: String, val port: Int, val arrivalsEvery: Duration, val outboxEvery: Duration)

private val settings: Module =
    config<Settings>("petshop") {
        Settings(string("host"), int("port"), duration("arrivalsEvery"), duration("outboxEvery"))
    }

private val telemetry: Module =
    // Added to Micrometer's global composite, which is where lark-micrometer writes by default.
    singleOf<PrometheusMeterRegistry>(
        { PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also(MicrometerRegistries::addRegistry) },
        { registry -> MicrometerRegistries.removeRegistry(registry); registry.close() },
    ) +
    closingOf<OpenTelemetrySdk> { tracing() } +
        // boundTo because `getTracer` returns the platform type `Tracer!`, which no key matches.
        single { sdk: OpenTelemetrySdk -> sdk.getTracer("petshop") }.boundTo<Tracer>()

/**
 * Spans go out over OTLP when `OTEL_EXPORTER_OTLP_ENDPOINT` names a collector, and nowhere otherwise, which is every
 * test. The rest of `OTEL_*` is read as OpenTelemetry documents it.
 */
private fun tracing(): OpenTelemetrySdk =
    AutoConfiguredOpenTelemetrySdk.builder()
        .addPropertiesSupplier {
            val exporting = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != null
            mapOf(
                "otel.service.name" to "petshop",
                "otel.traces.exporter" to if (exporting) "otlp" else "none",
                // OTLP over HTTP, on 4318: the SDK's default is gRPC, on 4317.
                "otel.exporter.otlp.protocol" to "http/protobuf",
                "otel.metrics.exporter" to "none",
                "otel.logs.exporter" to "none",
            )
        }
        .build()
        .openTelemetrySdk

private val web: Module =
    single { on: Binders, codecs: Codecs, shop: PetShop, health: Health, registry: PrometheusMeterRegistry,
        projection: Projection, feed: Feed, sdk: OpenTelemetrySdk ->
        petshopApi(on, codecs, shop, health, registry::scrape, projection::tally, feed::watch, sdk)
    } +
        single { api: Api, config: Settings, engine: Engine, codec: Codec, _: Arrivals, _: OutboxRelay,
            _: ReadinessGauges, _: JvmGcMetrics ->
            install({ engine.serve(api, config, codec) }) { server, _ -> server.stop() }.also { server ->
                onStarted {
                    logInfo(
                        "Petshop on ${server.baseUrl} (${server.stack}, ${server.codec}), docs at /api-docs, " +
                            "reference at /reference",
                    )
                }
            }
        }

/** What the shop reads at start, its layers kept apart so the report can say what each setting overrode. */
private val layers: Layered = layeredConfig()

private val infrastructure: Module = settings + telemetry + database + engine + codecs + registry
private val shop: Module = theShop + checks + meters + arrivals
private val events: Module = outbox + feed + projection + bus

object Petshop : LarkApp<Shopfront>() {
    override val module: Module = configOf(layers.config) + infrastructure + shop + events + web
}

/**
 * The petshop section and where each value came from, for `main` to log before the graph starts. DATABASE_URL often
 * carries a password. Each layer is cut to `petshop` so Pekko's reference.conf and the JVM's properties stay out.
 */
fun configReport(): String =
    layeredConfigOf(*layers.layers.map { it.name to it.config.withOnlyPath("petshop") }.toTypedArray())
        .origins(Secrets.default.and("petshop.database.url"))
        .report()
