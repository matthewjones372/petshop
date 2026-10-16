package petshop.app

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.raise.either
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.actor.actor
import io.github.matthewjones372.lark.app.actor.actors
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.logSpan
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.metricTagged
import io.github.matthewjones372.lark.otel.tracedSpan
import io.github.matthewjones372.lark.timed
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import kotlin.time.Duration.Companion.seconds
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

val opening: List<Pet> = listOf(
    Pet(PetId(1), "Nibbles", Species.Tortoise),
    Pet(PetId(2), "Barnaby", Species.Dog),
    Pet(PetId(3), "Mrs Peel", Species.Cat),
)

class ActorPetShop(
    private val ref: ActorRef<Shop>,
    private val window: ShopWindow,
    private val tracer: Tracer,
    private val registry: ChipRegistry,
) : PetShop {

    override fun all(): List<Pet> = window.pets()

    override fun find(id: PetId): Pet? = window.find(id)

    /** OpenTelemetry's context lives in a `LarkLocal` here; a `ThreadLocal` would not survive the ask. */
    override fun adopt(id: PetId, by: String): Either<PetShopError, Pet> =
        logAnnotated("pet_id" to id.value.toString(), "adopted_by" to by) {
            tracer.tracedSpan("adopt") {
                // Every line about this adoption carries its trace, so a log line leads to the trace in Tempo.
                logAnnotated("trace_id" to Span.current().spanContext.traceId) {
                    logSpan("adopt") {
                        // Timed around the ask so refusals, often the slow calls, are in the distribution.
                        timed("petshop.adopt.duration") {
                            handOver(id, by).also { answer ->
                                answer.fold(
                                    { no ->
                                        logWarn(no.message)
                                        // Every branch tags only "outcome": Prometheus needs one label
                                        // set per metric name and silently drops a series with another.
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
        }

    /** The actor decides first, so a lost race never calls the registry. */
    private fun handOver(id: PetId, by: String): Either<PetShopError, Pet> = either {
        val pet = ref.asked { reply -> Adopt(id, by, reply) }.bind()
        registry.lookup(id)
            .flatMap { chip -> registry.transfer(chip, to = by) }
            .onLeft { failure ->
                logWarn("registry refused pet ${id.value}: $failure")
                // The adoption is already on the shelf. Wait until the undo is too, so this call does not return
                // with the pet still looking taken. A read during the registry call can still see it taken.
                ref.ask(within = 3.seconds) { reply -> Returned(id, reply) }
                    .onLeft { unanswered -> logWarn("the return of pet ${id.value} was not confirmed: $unanswered") }
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

/** Throws when the actor stopped or timed out: that is a fault (a 500), not a declared refusal. */
private fun <A : Any> ActorRef<Shop>.asked(message: (Reply<A>) -> Shop): A =
    ask(within = 3.seconds, message).getOrElse { failure -> error("the shop did not answer: $failure") }

private fun PetShopError.outcome(): String = when (this) {
    is NoSuchPet -> "no_such_pet"
    is AlreadyAdopted -> "already_adopted"
    is NotChipped -> "not_chipped"
    is RegistryDown -> "registry_down"
    is NotRecorded -> "not_recorded"
}

val theShop: Module =
    actors() +
        singleOf(::ShopWindow) +
        // shelf reads the pets table, so a restart opens the shop as it was left.
        actor<Shop, Outbox, ShopWindow>("shop") { outbox, window ->
            shop(outbox, window, outbox.shelf(opening).associateBy { it.id })
        } +
        singleOf(::ActorPetShop).boundTo<PetShop>()
