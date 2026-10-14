package petshop.app

import arrow.core.Either
import arrow.core.Option
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

val theShop: Module =
    // The actors' flock, held open for the graph's life; the shop is an actor in it.
    actors() +
        // The catalogue comes from the pets table, so a restart opens the shop as it was left.
        actor<Shop, Outbox>("shop") { outbox -> shop(outbox, outbox.shelf(opening).associateBy { it.id }) } +
        singleOf(::ActorPetShop).boundTo<PetShop>()
