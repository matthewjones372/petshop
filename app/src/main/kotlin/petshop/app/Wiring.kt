package petshop.app

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.either
import io.github.matthewjones372.lark.app.AppScope
import io.github.matthewjones372.lark.app.Health
import io.github.matthewjones372.lark.app.HealthRegistry
import io.github.matthewjones372.lark.app.LarkApp
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.probe
import arrow.core.Option
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.pekko.ask
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.metricTagged
import io.github.matthewjones372.lark.timed
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.logSpan
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.otel.tracedSpan
import io.opentelemetry.api.trace.Tracer
import io.micrometer.core.instrument.Metrics as MicrometerRegistries
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.github.matthewjones372.lark.app.pekko.actor
import io.github.matthewjones372.lark.app.typesafe.config
import io.github.matthewjones372.lark.app.typesafe.loadedConfig
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pekko.PelicanServer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.javadsl.Adapter
import petshop.api.Healthy
import petshop.api.petshopApi
import petshop.domain.AlreadyAdopted
import petshop.domain.ChipRegistry
import petshop.domain.NoSuchPet
import petshop.domain.NotChipped
import petshop.domain.NotRegistered
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.PetShopError
import petshop.domain.RegistryDown
import petshop.domain.RegistryError
import petshop.domain.Unreachable
import petshop.domain.Species
import kotlin.reflect.typeOf
import kotlin.time.Duration
import org.apache.pekko.actor.typed.ActorSystem as TypedSystem
import kotlin.time.Duration.Companion.seconds

data class Settings(val port: Int, val arrivalsEvery: Duration)

/** The catalogue the shop opens with, before any arrival. */
val opening: List<Pet> = listOf(
    Pet(PetId(1), "Nibbles", Species.Tortoise),
    Pet(PetId(2), "Barnaby", Species.Dog),
    Pet(PetId(3), "Mrs Peel", Species.Cat),
)

/** Reads and writes go to the actor, so there is one writer and no lock anywhere in this file. */
class ActorPetShop(
    private val ref: ActorRef<Shop>,
    private val system: ActorSystem,
    private val tracer: Tracer,
    private val registry: ChipRegistry,
) : PetShop {

    override fun all(): List<Pet> = ref.ask(system, asking) { replyTo -> Everything(replyTo) }

    override fun find(id: PetId): Pet? =
        ref.ask(system, asking) { replyTo: ActorRef<Option<Pet>> -> Find(id, replyTo) }
            .fold({ null }, { pet -> pet })

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
        val pet = ref.ask(system, asking) { replyTo -> Adopt(id, by, replyTo) }.bind()
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

private val asking = 3.seconds

/** The shape of a refusal as a tag: few values, known before the code runs, which is what a tag is. */
private fun PetShopError.outcome(): String = when (this) {
    is NoSuchPet -> "no_such_pet"
    is AlreadyAdopted -> "already_adopted"
    is NotChipped -> "not_chipped"
    is RegistryDown -> "registry_down"
}

private val settings: Module =
    loadedConfig() + config<Settings>("petshop") { Settings(int("port"), duration("arrivalsEvery")) }

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
        single { sdk: OpenTelemetrySdk -> sdk.getTracer("petshop") }.boundTo<Tracer>()

private val theShop: Module =
    singleOf<ActorSystem>({ ActorSystem.create("petshop") }, { system -> system.terminate() }) +
        // The typed view of the same system. Two types, two keys, and the one that spawns actors is
        // not the one Pelican binds a port with.
        single { classic: ActorSystem -> Adapter.toTyped(classic) }.boundTo<TypedSystem<Void>>() +
        actor<Shop>("shop") { shop(opening.associateBy { it.id }) } +
        singleOf(::ActorPetShop).boundTo<PetShop>()
            .probe("shop", timeout = 3.seconds) { shop: PetShop -> shop.all().isNotEmpty() }

private val web: Module =
    // The port is a resource like any other: bound here, unbound when the graph is given back, which
    // is what lets a load test start the whole application in its own process.
    singleOf(
        { shop: PetShop, config: Settings, system: TypedSystem<Void>, health: HealthRegistry,
            registry: PrometheusMeterRegistry, _: Arrivals ->
            petshopApi(shop, { asked(health) }, registry::scrape)
                .startWithDocs(system, port = config.port, docs = docs { docsPath = "/api-docs" })
        },
        { server -> server.stop() },
    )

/** The registry's answer in the shape the endpoint publishes. */
private fun asked(health: HealthRegistry): Healthy = when (val readiness = health.readiness()) {
    is Health.Up -> Healthy(ready = true, failing = emptyList())
    is Health.Degraded -> Healthy(ready = true, failing = readiness.failing)
    is Health.Down -> Healthy(ready = false, failing = readiness.failing)
}

val petshop: Module = settings + telemetry + registry + theShop + arrivals + web

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
