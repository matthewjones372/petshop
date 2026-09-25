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
import petshop.domain.PetId
import petshop.domain.PetShopError

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

fun shop(pets: Map<PetId, Pet> = emptyMap()): Behavior<Shop> =
    Behaviors.receive(Shop::class.java)
        .onMessage(Arrived::class.java) { arrival -> shop(pets + (arrival.pet.id to arrival.pet)) }
        .onMessage(Returned::class.java) { returned ->
            pets[returned.id]?.let { pet -> shop(pets + (pet.id to pet.copy(adopted = false))) } ?: Behaviors.same()
        }
        .onMessage(Everything::class.java) { asked ->
            asked.replyTo.tell(pets.values.sortedBy { it.id.value })
            Behaviors.same()
        }
        .onMessage(Find::class.java) { asked ->
            asked.replyTo.tell(Option.fromNullable(pets[asked.id]))
            Behaviors.same()
        }
        .onMessage(Adopt::class.java) { asked ->
            val pet = pets[asked.id]
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
                    shop(pets + (taken.id to taken))
                }
            }
        }
        .build()
