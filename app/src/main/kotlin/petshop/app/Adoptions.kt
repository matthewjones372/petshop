package petshop.app

import arrow.core.Either
import arrow.core.Option
import arrow.core.left
import arrow.core.right
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.javadsl.Behaviors
import petshop.domain.AlreadyAdopted
import petshop.domain.NoSuchPet
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

data class Everything(val replyTo: ActorRef<List<Pet>>) : Shop

/**
 * The reply is an [Option] rather than a `Pet?`. Pekko refuses a null message, so an actor answering
 * "no such pet" with `null` throws where it meant to answer — and Kotlin's nullable type does not
 * stop you writing it.
 */
data class Find(val id: PetId, val replyTo: ActorRef<Option<Pet>>) : Shop

data class Adopt(val id: PetId, val by: String, val replyTo: ActorRef<Either<PetShopError, Pet>>) : Shop

/**
 * An adoption undone: the actor said yes, and then the registry would not record the new keeper. The
 * pet goes back on the shelf rather than out of the door untraceable.
 */
data class Returned(val id: PetId) : Shop

/**
 * The event goes into the outbox table before the pet changes, and the pet changes only if it went:
 * there is no moment where the shop has sold a pet and not recorded that it did.
 *
 * A write that throws — Postgres down, the pool exhausted — leaves the actor as it was rather than
 * stopping it. Nothing is answered, so whoever asked times out instead of being told yes about a sale
 * nobody recorded, and the pet is still on the shelf for the next one.
 */
private class State(val pets: Map<PetId, Pet>, private val outbox: Outbox) {

    fun record(pet: Pet, event: (seq: Long) -> ShopEvent): State {
        outbox.record(event)
        return State(pets + (pet.id to pet), outbox)
    }
}

fun shop(outbox: Outbox, pets: Map<PetId, Pet> = emptyMap()): Behavior<Shop> =
    Behaviors.supervise(shop(State(pets, outbox)))
        .onFailure(Exception::class.java, SupervisorStrategy.resume())

/**
 * One `when` over the sealed [Shop], so a new message is a compile error here until it is handled.
 * The state is the argument: a change is the next behaviour, and nothing in here is reassigned.
 */
private fun shop(state: State): Behavior<Shop> =
    Behaviors.receiveMessage<Shop> { message ->
        when (message) {
            is Arrived -> shop(state.record(message.pet) { seq -> PetArrived(seq, message.pet) })
            is Adopt -> adopt(state, message)
            is Returned -> returned(state, message.id)
            is Everything -> message.replyTo.answered(state.pets.values.sortedBy { it.id.value })
            is Find -> message.replyTo.answered(Option.fromNullable(state.pets[message.id]))
        }
    }

private fun adopt(state: State, asked: Adopt): Behavior<Shop> {
    val pet = state.pets[asked.id]
    return when {
        pet == null -> asked.replyTo.answered(NoSuchPet(asked.id.value).left())
        pet.adopted -> asked.replyTo.answered(AlreadyAdopted(asked.id.value).left())
        else -> {
            val taken = pet.copy(adopted = true)
            val next = state.record(taken) { seq -> PetAdopted(seq, taken, asked.by) }
            asked.replyTo.tell(taken.right())
            shop(next)
        }
    }
}

/**
 * The adoption was already recorded, and may already be on the bus, so the undo is an event of its own
 * rather than a quiet edit: a consumer that counted the adoption hears it reversed.
 */
private fun returned(state: State, id: PetId): Behavior<Shop> =
    state.pets[id]?.let { pet ->
        val back = pet.copy(adopted = false)
        shop(state.record(back) { seq -> PetReturned(seq, back) })
    } ?: Behaviors.same()

/** A question changes nothing: the reply goes, and the behaviour stays as it was. */
private fun <A : Any> ActorRef<A>.answered(reply: A): Behavior<Shop> {
    tell(reply)
    return Behaviors.same()
}
