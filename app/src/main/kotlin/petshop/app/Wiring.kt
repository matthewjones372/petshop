package petshop.app

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.app.AppScope
import io.github.matthewjones372.lark.app.LarkApp
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.typesafe.Secrets
import io.github.matthewjones372.lark.app.typesafe.config
import io.github.matthewjones372.lark.app.typesafe.configOf
import io.github.matthewjones372.lark.app.typesafe.origins
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.pelican.health.Health
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.micrometer.core.instrument.Metrics as MicrometerRegistries
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
    singleOf<OpenTelemetrySdk>({ OpenTelemetrySdk.builder().build() }, { sdk -> sdk.close() }) +
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
    singleOf(
        { shop: PetShop, config: Settings, system: TypedSystem<Void>, health: Health,
            registry: PrometheusMeterRegistry, projection: Projection, _: Arrivals, _: OutboxRelay, _: Meters ->
            petshopApi(shop, health, registry::scrape, projection::tally)
                .startWithDocs(system, port = config.port, host = config.host, docs = petshopDocs)
        },
        { server -> server.stop() },
    )

fun petshopFrom(conf: Config): Module =
    configOf(conf) + settings + telemetry + database + http + registry + theShop + checks + meters + arrivals +
        events + bus(conf) + web

object Petshop : LarkApp<PelicanServer>() {

    private val conf: Config = ConfigFactory.load()

    override val module: Module = petshopFrom(conf)

    override fun AppScope.run(root: PelicanServer) {
        logInfo(reported())
        logInfo("Petshop on ${root.baseUrl}, docs at ${root.baseUrl}/api-docs, reference at ${root.baseUrl}/reference")
        // Not `root.block()`: it waits for the graph's release, so a SIGTERM would sit out lark's whole grace
        // period instead of stopping the relay and committing Kafka offsets.
        awaitShutdown()
    }

    // DATABASE_URL often carries a password. Not layeredConfig(): lark 0.9.2's origins() throws on
    // `${?ENV}` pairs (fixed in lark#372).
    private fun reported(): String =
        conf.withOnlyPath("petshop").origins(Secrets.default.and("petshop.database.url")).report()
}
