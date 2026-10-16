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
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.client.jetty.JettyHttpTransport
import io.github.matthewjones372.pelican.health.Health
import io.github.matthewjones372.pelican.jetty.PelicanServer
import io.github.matthewjones372.pelican.jetty.docs.startWithDocs
import io.micrometer.core.instrument.Metrics as MicrometerRegistries
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import kotlin.time.Duration
import petshop.api.petshopApi
import petshop.api.petshopDocs
import petshop.api.shopCodecs
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

/** Lark's streams run on virtual threads, and Jetty's client calls the registry. */
private val jetty: Module =
    single<StreamBackend> { Forks() } +
        closingOf { -> JettyHttpTransport() }

private val web: Module =
    single { streams: StreamBackend, shop: PetShop, window: ShopWindow, health: Health,
        registry: PrometheusMeterRegistry, projection: Projection, feed: Feed, sdk: OpenTelemetrySdk ->
        petshopApi(
            streams,
            shop,
            health,
            registry::scrape,
            projection::tally,
            feed::watch,
            sdk,
            listed = window::listed,
            codecs = shopCodecs(window::listed, window::json, window::bytes),
            requests = { counter("petshop.requests").increment() },
        )
    } +
        single { api: Api, config: Settings, _: Arrivals, _: OutboxRelay, _: ReadinessGauges, _: JvmGcMetrics ->
            install({ api.startWithDocs(port = config.port, host = config.host, docs = petshopDocs) }) { server, _ ->
                server.stop()
            }.also { server ->
                onStarted { logInfo("Petshop on ${server.baseUrl}, docs at /api-docs, reference at /reference") }
            }
        }

/** What the shop reads at start, its layers kept apart so the report can say what each setting overrode. */
private val layers: Layered = layeredConfig()

private val infrastructure: Module = settings + telemetry + database + jetty + registry
private val shop: Module = theShop + checks + meters + arrivals
private val events: Module = outbox + feed + projection + bus

object Petshop : LarkApp<PelicanServer>() {
    override val module: Module = configOf(layers.config) + infrastructure + shop + events + web
}

/**
 * The petshop section and where each value came from, for `main` to log before the graph starts. DATABASE_URL often
 * carries a password. Each layer is cut to `petshop` so libraries' reference.conf and the JVM's properties stay out.
 */
fun configReport(): String =
    layeredConfigOf(*layers.layers.map { it.name to it.config.withOnlyPath("petshop") }.toTypedArray())
        .origins(Secrets.default.and("petshop.database.url"))
        .report()
