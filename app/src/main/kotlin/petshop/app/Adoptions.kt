package petshop.app

import arrow.core.Either
import arrow.core.Option
import arrow.core.left
import arrow.core.right
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
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

/** Up to [limit] of the events nobody has confirmed publishing, oldest first. */
data class Unsent(val limit: Int, val replyTo: ActorRef<List<ShopEvent>>) : Shop

/** The relay's word that [seq] reached the bus, so it can leave the outbox. */
data class Sent(val seq: Long) : Shop

/**
 * The pets and the outbox are one value, so a change and the event saying it happened are one
 * transition: there is no moment where the shop has sold a pet and not yet recorded that it did.
 */
private data class State(val pets: Map<PetId, Pet>, val outbox: List<ShopEvent>, val recorded: Long) {

    fun record(pet: Pet, event: (seq: Long) -> ShopEvent): State =
        State(pets + (pet.id to pet), outbox + event(recorded + 1), recorded + 1)
}

fun shop(pets: Map<PetId, Pet> = emptyMap()): Behavior<Shop> = shop(State(pets, emptyList(), 0))

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
            is Unsent -> message.replyTo.answered(state.outbox.take(message.limit))
            is Sent -> shop(state.copy(outbox = state.outbox.filterNot { it.seq == message.seq }))
        }
    }

private fun adopt(state: State, asked: Adopt): Behavior<Shop> {
    val pet = state.pets[asked.id]
    return when {
        pet == null -> asked.replyTo.answered(NoSuchPet(asked.id.value).left())
        pet.adopted -> asked.replyTo.answered(AlreadyAdopted(asked.id.value).left())
        else -> {
            val taken = pet.copy(adopted = true)
            asked.replyTo.tell(taken.right())
            shop(state.record(taken) { seq -> PetAdopted(seq, taken, asked.by) })
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
