package petshop.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.actor.Behaviour
import io.github.matthewjones372.lark.actor.Next
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.onStart
import io.github.matthewjones372.lark.actor.stay
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.typeOf
import petshop.api.PetDto
import petshop.api.ShopCodecs
import petshop.api.toDto
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

/** The shop's only writer: one message at a time, so of two racing adopters the second is refused. */
sealed interface Shop

data class Arrived(val pet: Pet) : Shop

data class Adopt(val id: PetId, val by: String, val reply: Reply<Either<PetShopError, Pet>>) : Shop

data class Returned(val id: PetId, val reply: Reply<Unit>) : Shop

class ShopWindow {
    private val shown = AtomicReference(Shelf())

    fun pets(): List<Pet> = shown.get().pets

    fun find(id: PetId): Pet? = shown.get().byId[id]

    fun listed(): List<PetDto> = shown.get().listed

    fun json(): String = shown.get().json

    internal fun show(pets: Map<PetId, Pet>) {
        val now = shown.get()
        if (now.byId === pets) return
        val ordered = pets.values.sortedBy { it.id.value }
        val listed = ordered.toDto()
        shown.set(Shelf(ordered, pets, listed, catalogueJson.encodeToString(listed)))
    }
}

private class Shelf(
    val pets: List<Pet> = emptyList(),
    val byId: Map<PetId, Pet> = emptyMap(),
    val listed: List<PetDto> = emptyList(),
    val json: String = catalogueJson.encodeToString(emptyList<PetDto>()),
)

private val catalogueJson = ShopCodecs.codec<List<PetDto>>(typeOf<List<PetDto>>())

private class State(val pets: Map<PetId, Pet>, private val outbox: Outbox, private val window: ShopWindow) {

    /** The pet's row and its event are one transaction, so the state changes only once both are durable. */
    fun record(pet: Pet, event: (seq: Long) -> ShopEvent): Either<Throwable, State> =
        Either.catch { outbox.record(pet, event) }.map {
            State(pets + (pet.id to pet), outbox, window).also { next -> window.show(next.pets) }
        }
}

/** Every write is caught where it is made: a throw out of a step would restart the actor from its opening catalogue. */
fun shop(outbox: Outbox, window: ShopWindow, pets: Map<PetId, Pet> = emptyMap()): Behaviour<Shop, *, Nothing> {
    window.show(pets)
    return behaviour<Shop, State>(State(pets, outbox, window)) { _, state, message ->
        when (message) {
            is Arrived -> recorded("the arrival of ${message.pet.name}") {
                state.record(message.pet) { seq -> PetArrived(seq, message.pet) }
            }
            is Adopt -> adopt(state, message)
            is Returned -> returned(state, message.id).also { message.reply(Unit) }
        }
    }.onStart { window.show(pets) }
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

/** The adoption may already be on the bus, so the undo is its own event and consumers hear it reversed. */
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
