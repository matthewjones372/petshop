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

private fun shop(state: State): Behavior<Shop> =
    Behaviors.receive(Shop::class.java)
        .onMessage(Arrived::class.java) { arrival ->
            shop(state.record(arrival.pet) { seq -> PetArrived(seq, arrival.pet) })
        }
        .onMessage(Everything::class.java) { asked ->
            asked.replyTo.tell(state.pets.values.sortedBy { it.id.value })
            Behaviors.same()
        }
        .onMessage(Find::class.java) { asked ->
            asked.replyTo.tell(Option.fromNullable(state.pets[asked.id]))
            Behaviors.same()
        }
        .onMessage(Adopt::class.java) { asked ->
            val pet = state.pets[asked.id]
            when {
                pet == null -> {
                    asked.replyTo.tell(NoSuchPet(asked.id.value).left())
                    Behaviors.same()
                }
                pet.adopted -> {
                    asked.replyTo.tell(AlreadyAdopted(asked.id.value).left())
                    Behaviors.same()
                }
                else -> {
                    val taken = pet.copy(adopted = true)
                    asked.replyTo.tell(taken.right())
                    shop(state.record(taken) { seq -> PetAdopted(seq, taken, asked.by) })
                }
            }
        }
        .onMessage(Unsent::class.java) { asked ->
            asked.replyTo.tell(state.outbox.take(asked.limit))
            Behaviors.same()
        }
        .onMessage(Sent::class.java) { sent ->
            shop(state.copy(outbox = state.outbox.filterNot { it.seq == sent.seq }))
        }
        .build()
