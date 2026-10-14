package petshop.app

import com.typesafe.config.Config
import io.github.matthewjones372.lark.app.AppScope
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
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.health.Health
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.micrometer.core.instrument.Metrics as MicrometerRegistries
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import kotlin.time.Duration
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorSystem as TypedSystem
import org.apache.pekko.actor.typed.javadsl.Adapter
import petshop.api.petshopApi
import petshop.api.petshopDocs
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
    closingOf<OpenTelemetrySdk> { OpenTelemetrySdk.builder().build() } +
        // boundTo because `getTracer` returns the platform type `Tracer!`, which no key matches.
        single { sdk: OpenTelemetrySdk -> sdk.getTracer("petshop") }.boundTo<Tracer>()

/** Pekko is for HTTP only; the shop's actor and every stream run on Lark. */
private val http: Module =
    singleOf<ActorSystem>({ ActorSystem.create("petshop") }, { system -> system.terminate() }) +
        single { classic: ActorSystem -> Adapter.toTyped(classic) }.boundTo<TypedSystem<Void>>()

private val events: Module =
    // The one place that picks the stream backend; a test overrides it to run the relay on its own clock.
    single { -> Forks() }.boundTo<StreamBackend>() +
        outbox +
        projection

private val web: Module =
    single { shop: PetShop, health: Health, registry: PrometheusMeterRegistry, projection: Projection ->
        petshopApi(shop, health, registry::scrape, projection::tally)
    } +
        singleOf(
            { api: Api, config: Settings, system: TypedSystem<Void>, _: Arrivals, _: OutboxRelay, _: ReadinessGauges,
                _: JvmGcMetrics ->
                api.startWithDocs(system, port = config.port, host = config.host, docs = petshopDocs)
            },
            { server -> server.stop() },
        )

fun petshopFrom(conf: Config): Module =
    configOf(conf) + settings + telemetry + database + http + registry + theShop + checks + meters + arrivals +
        events + bus(conf) + web

object Petshop : LarkApp<PelicanServer>() {

    private val layers: Layered = layeredConfig()

    override val module: Module = petshopFrom(layers.config)

    override fun AppScope.run(root: PelicanServer) {
        logInfo(reported())
        logInfo("Petshop on ${root.baseUrl}, docs at ${root.baseUrl}/api-docs, reference at ${root.baseUrl}/reference")
        // Not `root.block()`: it waits for the graph's release, so a SIGTERM would sit out lark's whole grace
        // period instead of stopping the relay and committing Kafka offsets.
        awaitShutdown()
    }

    // DATABASE_URL often carries a password. Each layer is cut to `petshop` so Pekko's reference.conf and the
    // JVM's properties stay out of the report.
    private fun reported(): String =
        layeredConfigOf(*layers.layers.map { it.name to it.config.withOnlyPath("petshop") }.toTypedArray())
            .origins(Secrets.default.and("petshop.database.url"))
            .report()
}
