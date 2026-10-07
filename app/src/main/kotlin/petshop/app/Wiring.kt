package petshop.app

import arrow.core.Either
import arrow.core.Option
import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.raise.either
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.app.AppScope
import io.github.matthewjones372.lark.app.HealthRegistry
import io.github.matthewjones372.lark.app.LarkApp
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.actor.actor
import io.github.matthewjones372.lark.app.actor.actors
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.probe
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.typesafe.config
import io.github.matthewjones372.lark.app.typesafe.loadedConfig
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.logSpan
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.metricTagged
import io.github.matthewjones372.lark.otel.tracedSpan
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.timed
import io.github.matthewjones372.pelican.health.Status
import io.github.matthewjones372.pelican.health.health
import io.github.matthewjones372.pelican.health.heapHeadroom
import io.github.matthewjones372.pelican.health.jdbc
import io.github.matthewjones372.pelican.health.noDeadlockedThreads
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.micrometer.core.instrument.Metrics as MicrometerRegistries
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import kotlin.reflect.typeOf
import kotlin.time.Duration
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorSystem as TypedSystem
import org.apache.pekko.actor.typed.javadsl.Adapter
import petshop.api.petshopApi
import petshop.domain.AlreadyAdopted
import petshop.domain.ChipRegistry
import petshop.domain.NoSuchPet
import petshop.domain.NotChipped
import petshop.domain.NotRecorded
import petshop.domain.NotRegistered
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.PetShopError
import petshop.domain.RegistryDown
import petshop.domain.RegistryError
import petshop.domain.Species
import petshop.domain.Unreachable

/** [host] is the interface the port is bound on: loopback unless something outside this machine has to reach it. */
data class Settings(val host: String, val port: Int, val arrivalsEvery: Duration, val outboxEvery: Duration)

/** The catalogue the shop opens with, before any arrival. */
val opening: List<Pet> = listOf(
    Pet(PetId(1), "Nibbles", Species.Tortoise),
    Pet(PetId(2), "Barnaby", Species.Dog),
    Pet(PetId(3), "Mrs Peel", Species.Cat),
)

/** Reads and writes go to the actor, so there is one writer and no lock anywhere in this file. */
class ActorPetShop(
    private val ref: ActorRef<Shop>,
    private val tracer: Tracer,
    private val registry: ChipRegistry,
) : PetShop {

    override fun all(): List<Pet> = ref.asked { reply -> Everything(reply) }

    override fun find(id: PetId): Pet? =
        ref.asked { reply: Reply<Option<Pet>> -> Find(id, reply) }.fold({ null }, { pet -> pet })

    /**
     * A span whose trace id is on every line written inside it, including the ones a fork writes:
     * OpenTelemetry's context lives in a `LarkLocal` while `lark-otel` is on the classpath, and a
     * `ThreadLocal` would not survive the ask.
     *
     * The pet and the adopter are annotations rather than words in the message, so a search for one
     * pet finds every line about it — the refusal included.
     */
    override fun adopt(id: PetId, by: String): Either<PetShopError, Pet> =
        logAnnotated("pet_id" to id.value.toString(), "adopted_by" to by) {
            tracer.tracedSpan("adopt") {
                logSpan("adopt") {
                    // Timed around the ask, so a refusal is in the distribution too: the slow calls
                    // are the ones worth seeing and most of them are the ones that failed.
                    timed("petshop.adopt.duration") {
                        handOver(id, by).also { answer ->
                            answer.fold(
                                { no ->
                                    logWarn(no.message)
                                    // The outcome is a tag and not a name, so one query answers
                                    // "how many adoptions" and one answers "how many were refused".
                                    //
                                    // Every branch tags the same key and only that key: Prometheus
                                    // requires one set of label names per metric name, and a series
                                    // registered with a different set is dropped without a word.
                                    metricTagged("outcome" to no.outcome()) {
                                        counter("petshop.adoptions").increment()
                                    }
                                },
                                { pet ->
                                    logInfo("adopted ${pet.name}")
                                    metricTagged("outcome" to "taken") {
                                        counter("petshop.adoptions").increment()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

    /**
     * The actor decides who gets the pet, and only then is the registry asked: a race is lost in the
     * shop without costing the loser a call to somebody else's service, and a pet somebody already has
     * never reaches the registry at all.
     *
     * If the registry cannot record the new keeper, the pet goes back on the shelf. The shop does not
     * hand over a pet nobody could trace, and it does not keep one it has already refused to sell.
     */
    private fun handOver(id: PetId, by: String): Either<PetShopError, Pet> = either {
        val pet = ref.asked { reply -> Adopt(id, by, reply) }.bind()
        registry.lookup(id)
            .flatMap { chip -> registry.transfer(chip, to = by) }
            .onLeft { failure ->
                logWarn("registry refused pet ${id.value}: $failure")
                ref.tell(Returned(id))
            }
            .mapLeft { failure -> failure.refusing(id) }
            .bind()
        pet
    }
}

private fun RegistryError.refusing(id: PetId): PetShopError = when (this) {
    NotRegistered -> NotChipped(id.value)
    is Unreachable -> RegistryDown(id.value)
}

/**
 * The actor's answer, or a throw when it gave none: an actor that stopped or did not answer in time is a
 * fault in the shop, which the endpoint answers as a 500, not a refusal it declared.
 */
private fun <A : Any> ActorRef<Shop>.asked(message: (Reply<A>) -> Shop): A =
    ask(within = 3.seconds, message).getOrElse { failure -> error("the shop did not answer: $failure") }

/** The shape of a refusal as a tag: few values, known before the code runs, which is what a tag is. */
private fun PetShopError.outcome(): String = when (this) {
    is NoSuchPet -> "no_such_pet"
    is AlreadyAdopted -> "already_adopted"
    is NotChipped -> "not_chipped"
    is RegistryDown -> "registry_down"
    is NotRecorded -> "not_recorded"
}

private val settings: Module =
    loadedConfig() + config<Settings>("petshop") {
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
            { registry: PrometheusMeterRegistry, health: HealthRegistry -> Meters(registry, health) },
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

private val theShop: Module =
    // The actors' flock, held open for the graph's life; the shop is an actor in it.
    actors() +
        // The catalogue comes from the pets table, so a restart opens the shop as it was left.
        actor<Shop, Outbox>("shop") { outbox -> shop(outbox, outbox.shelf(opening).associateBy { it.id }) } +
        singleOf(::ActorPetShop).boundTo<PetShop>()
            .probe("shop", timeout = 3.seconds) { shop: PetShop -> shop.all().isNotEmpty() }

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
        { shop: PetShop, config: Settings, system: TypedSystem<Void>, pool: DataSource,
            registry: PrometheusMeterRegistry, projection: Projection, _: Arrivals, _: OutboxRelay, _: Meters ->
            petshopApi(shop, probes(shop, pool), registry::scrape, projection::tally)
                .startWithDocs(system, port = config.port, host = config.host, docs = docs {
                    docsPath = "/api-docs"
                    shopReference()
                })
        },
        { server -> server.stop() },
    )

/**
 * What /health/live and /health/ready ask, each under its own timeout. Live is the process itself, so a failure
 * there gets it restarted; ready is what it needs to take traffic, so Postgres going takes it out of rotation
 * without restarting it.
 */
private fun probes(shop: PetShop, pool: DataSource) = health {
    live("threads") { noDeadlockedThreads() }
    // The actor answering at all: a shop that cannot is a process to restart, whatever the database is doing.
    live("shop", timeout = 3.seconds) {
        if (shop.all().isNotEmpty()) Status.Pass else Status.Fail("the shop answered with no pets")
    }
    ready("database", componentType = "datastore") { jdbc(pool) }
    // A warning, not a failure: a heap running short is worth a look before it is an outage.
    ready("heap", critical = false) { heapHeadroom(HEAP_HEADROOM_BYTES) }
}

private const val HEAP_HEADROOM_BYTES = 64L * 1024 * 1024

/** The whole service, its bus chosen by [conf]'s `petshop.bus`. */
fun petshopFrom(conf: Config): Module =
    settings + telemetry + database + http + registry + theShop + arrivals + events + bus(conf) + web

val petshop: Module = petshopFrom(ConfigFactory.load())

/**
 * The application as a value, so `main` is the leaving and the build can read the root it starts
 * from without running anything.
 */
object Petshop : LarkApp<PelicanServer>() {

    override val module: Module = petshop

    override fun AppScope.run(root: PelicanServer) {
        logInfo("Petshop on ${root.baseUrl}, docs at ${root.baseUrl}/api-docs")
        root.block()
    }
}
