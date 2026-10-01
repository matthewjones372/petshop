package petshop.app

import arrow.core.Either
import arrow.core.Option
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.actor.Behaviour
import io.github.matthewjones372.lark.actor.Next
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.stay
import petshop.domain.AlreadyAdopted
import petshop.domain.NoSuchPet
import petshop.domain.NotRecorded
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.PetReturned
import petshop.domain.PetShopError
import petshop.domain.ShopEvent

/**
 * The shop's only writer.
 *
 * Two people adopting the same tortoise at the same moment is the race this exists to lose on
 * purpose: an actor handles one message at a time, so the second one is told it is already adopted
 * rather than both being told yes.
 */
sealed interface Shop

data class Arrived(val pet: Pet) : Shop

data class Everything(val reply: Reply<List<Pet>>) : Shop

/** An [Option] because a reply is never null: `Reply<A : Any>` will not take a `Pet?`. */
data class Find(val id: PetId, val reply: Reply<Option<Pet>>) : Shop

data class Adopt(val id: PetId, val by: String, val reply: Reply<Either<PetShopError, Pet>>) : Shop

/**
 * An adoption undone: the actor said yes, and then the registry would not record the new keeper. The
 * pet goes back on the shelf rather than out of the door untraceable.
 */
data class Returned(val id: PetId) : Shop

/**
 * The event goes into the outbox table before the pet changes, and the pet changes only if it went:
 * there is no moment where the shop has sold a pet and not recorded that it did.
 */
private class State(val pets: Map<PetId, Pet>, private val outbox: Outbox) {

    /** The next state, or why the write failed: Postgres down, the pool exhausted. */
    fun record(pet: Pet, event: (seq: Long) -> ShopEvent): Either<Throwable, State> =
        Either.catch { outbox.record(event) }.map { State(pets + (pet.id to pet), outbox) }
}

/**
 * A write that fails leaves the actor as it was. Every write is caught where it is made, because a
 * throw out of a step would start the actor again from its opening catalogue: an adopter is told
 * [NotRecorded] and the pet is still on the shelf for the next one, and an arrival or a return nobody
 * is waiting on is logged and dropped.
 *
 * One `when` over the sealed [Shop], so a new message is a compile error here until it is handled.
 */
fun shop(outbox: Outbox, pets: Map<PetId, Pet> = emptyMap()): Behaviour<Shop, *, Nothing> =
    behaviour<Shop, State>(State(pets, outbox)) { _, state, message ->
        when (message) {
            is Arrived -> recorded("the arrival of ${message.pet.name}") {
                state.record(message.pet) { seq -> PetArrived(seq, message.pet) }
            }
            is Adopt -> adopt(state, message)
            is Returned -> returned(state, message.id)
            is Everything -> stay().also { message.reply(state.pets.values.sortedBy { it.id.value }) }
            is Find -> stay().also { message.reply(Option.fromNullable(state.pets[message.id])) }
        }
    }

private fun adopt(state: State, asked: Adopt): Next<State> {
    val pet = state.pets[asked.id]
    return when {
        pet == null -> stay().also { asked.reply(NoSuchPet(asked.id.value).left()) }
        pet.adopted -> stay().also { asked.reply(AlreadyAdopted(asked.id.value).left()) }
        else -> {
            val taken = pet.copy(adopted = true)
            state.record(taken) { seq -> PetAdopted(seq, taken, asked.by) }.fold(
                { failed ->
                    logWarn("the adoption of pet ${asked.id.value} could not be recorded: $failed")
                    stay().also { asked.reply(NotRecorded(asked.id.value).left()) }
                },
                { next -> become(next).also { asked.reply(taken.right()) } },
            )
        }
    }
}

/**
 * The adoption was already recorded, and may already be on the bus, so the undo is an event of its own
 * rather than a quiet edit: a consumer that counted the adoption hears it reversed.
 */
private fun returned(state: State, id: PetId): Next<State> =
    state.pets[id]?.let { pet ->
        val back = pet.copy(adopted = false)
        recorded("the return of pet ${id.value}") { state.record(back) { seq -> PetReturned(seq, back) } }
    } ?: stay()

private fun recorded(what: String, write: () -> Either<Throwable, State>): Next<State> =
    write().fold(
        { failed -> stay().also { logWarn("$what could not be recorded: $failed") } },
        { next -> become(next) },
    )
