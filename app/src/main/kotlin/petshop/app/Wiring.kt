package petshop.app

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.app.AppScope
import io.github.matthewjones372.lark.app.LarkApp
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.typesafe.config
import io.github.matthewjones372.lark.app.typesafe.configOf
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

/** [host] is the interface the port is bound on: loopback unless something outside this machine has to reach it. */
data class Settings(val host: String, val port: Int, val arrivalsEvery: Duration, val outboxEvery: Duration)

private val settings: Module =
    config<Settings>("petshop") {
        Settings(string("host"), int("port"), duration("arrivalsEvery"), duration("outboxEvery"))
    }

private val telemetry: Module =
    // Added to Micrometer's global composite, which is where lark-micrometer writes unless it is
    // handed a registry: the graph owns the registry's life, and nothing has to bind anything.
    singleOf<PrometheusMeterRegistry>(
        { PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also(MicrometerRegistries::addRegistry) },
        { registry -> MicrometerRegistries.removeRegistry(registry); registry.close() },
    ) +
    singleOf<OpenTelemetrySdk>({ OpenTelemetrySdk.builder().build() }, { sdk -> sdk.close() }) +
        // boundTo rather than a type argument: `getTracer` is Java, so the inferred key is the
        // platform type `Tracer!` that nothing matches — and naming the key as a type argument would
        // force naming the dependency as one too.
        single { sdk: OpenTelemetrySdk -> sdk.getTracer("petshop") }.boundTo<Tracer>() +
        // The JVM's meters and the shop's health, on the registry /metrics scrapes.
        singleOf(
            { registry: PrometheusMeterRegistry, readiness: Readiness -> Meters(registry, readiness) },
            { meters -> meters.close() },
        )

/**
 * Pekko, for HTTP and nothing else: Pelican's server binds the port on it, and the chip registry's
 * client sends through Pekko HTTP. The shop's actor and every stream run on Lark.
 */
private val http: Module =
    singleOf<ActorSystem>({ ActorSystem.create("petshop") }, { system -> system.terminate() }) +
        // The typed view of the same system, which is the one Pelican binds a port with.
        single { classic: ActorSystem -> Adapter.toTyped(classic) }.boundTo<TypedSystem<Void>>()

private val events: Module =
    // What the streams run on: the relay, the arrivals and the projection. Each describes its stream and
    // names no backend; this is the one place that decides, and a test that wants the relay on its own
    // clock overrides it. Forks runs a stream as one loop on a virtual thread, so the relay's claim blocks
    // on Postgres where it is and a stop interrupts it.
    single { -> Forks() }.boundTo<StreamBackend>() +
        outbox +
        projection

private val web: Module =
    // The port is a resource like any other: bound here, unbound when the graph is given back, which
    // is what lets a load test start the whole application in its own process.
    singleOf(
        { shop: PetShop, config: Settings, system: TypedSystem<Void>, health: Health,
            registry: PrometheusMeterRegistry, projection: Projection, _: Arrivals, _: OutboxRelay, _: Meters ->
            petshopApi(shop, health, registry::scrape, projection::tally)
                .startWithDocs(system, port = config.port, host = config.host, docs = petshopDocs)
        },
        { server -> server.stop() },
    )

/**
 * The whole service, read from [conf]: the document `petshop.bus` was chosen from is the one every
 * other section is read from, so a test assembling with its own configuration gets all of it.
 */
fun petshopFrom(conf: Config): Module =
    configOf(conf) + settings + telemetry + database + http + registry + theShop + checks + arrivals + events + bus(conf) + web

val petshop: Module = petshopFrom(ConfigFactory.load())

/**
 * The application as a value, so `main` is the leaving and the build can read the root it starts
 * from without running anything.
 */
object Petshop : LarkApp<PelicanServer>() {

    override val module: Module = petshop

    override fun AppScope.run(root: PelicanServer) {
        logInfo("Petshop on ${root.baseUrl}, docs at ${root.baseUrl}/api-docs, reference at ${root.baseUrl}/reference")
        root.block()
    }
}
