package petshop.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.AskFailure
import io.github.matthewjones372.lark.actor.Behaviour
import io.github.matthewjones372.lark.actor.Next
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.onStart
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.logError
import io.github.matthewjones372.lark.logWarn
import petshop.api.Catalogue
import petshop.api.PetDto
import petshop.api.toDto
import petshop.domain.Adopted
import petshop.domain.Adopting
import petshop.domain.AlreadyAdopted
import petshop.domain.BeingAdopted
import petshop.domain.NoSuchPet
import petshop.domain.NotRecorded
import petshop.domain.OnShelf
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.PetShopError
import petshop.domain.ShopEvent
import petshop.domain.Standing
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/** The shop's only writer: one message at a time, so of two racing adopters the second is refused. */
sealed interface Shop

data class Arrived(val pet: Pet) : Shop

data class Adopt(val id: PetId, val by: String, val reply: Reply<Either<PetShopError, Pet>>) : Shop

/** Only [Adoption] sends these two, so [Unsettled.Invalid] is a bug, never a refusal. */
data class Confirm(val id: PetId, val by: String, val reply: Reply<Either<Unsettled, Pet>>) : Shop

data class Return(val id: PetId, val by: String, val reply: Reply<Either<Unsettled, Pet>>) : Shop

sealed interface Unsettled {
    data object Unrecorded : Unsettled

    data class Invalid(val standing: Standing?) : Unsettled
}

data class Ping(val reply: Reply<Unit>) : Shop

fun ActorRef<Shop>.answers(within: Duration): Either<AskFailure, Unit> = ask(within) { reply -> Ping(reply) }

class ShopWindow {
    private val shown = AtomicReference(Shelf())

    fun pets(): List<Pet> = shown.get().pets

    fun find(id: PetId): Pet? = shown.get().byId[id]

    fun listed(): List<PetDto> = shown.get().listed

    internal fun show(pets: Map<PetId, Pet>) {
        val now = shown.get()
        if (now.byId === pets) return
        val ordered = pets.values.sortedBy { it.id.value }
        shown.set(Shelf(ordered, pets, Catalogue(ordered.toDto())))
    }
}

private class Shelf(
    val pets: List<Pet> = emptyList(),
    val byId: Map<PetId, Pet> = emptyMap(),
    val listed: List<PetDto> = Catalogue(emptyList()),
)

private class State(val pets: Map<PetId, Pet>, private val outbox: Outbox, private val window: ShopWindow) {

    /** The pet's row and its event are one transaction, so the state changes only once both are durable. */
    fun record(pet: Pet, event: (seq: Long) -> ShopEvent): Either<Throwable, State> =
        Either.catch { outbox.record(pet, event) }.map { with(pet) }

    fun keep(pet: Pet): Either<Throwable, State> = Either.catch { outbox.keep(pet) }.map { with(pet) }

    private fun with(pet: Pet) = State(pets + (pet.id to pet), outbox, window).also { next -> window.show(next.pets) }
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

            is Confirm -> confirm(state, message)

            is Return -> returned(state, message)

            is Ping -> stay().also { message.reply(Unit) }
        }
    }.onStart { window.show(pets) }
}

private fun adopt(state: State, asked: Adopt): Next<State> {
    val pet = state.pets[asked.id]
    return when (pet?.standing) {
        null -> stay().also { asked.reply(NoSuchPet(asked.id.value).left()) }

        is Adopting -> stay().also { asked.reply(BeingAdopted(asked.id.value).left()) }

        is Adopted -> stay().also { asked.reply(AlreadyAdopted(asked.id.value).left()) }

        OnShelf -> {
            val adopting = pet.copy(standing = Adopting(asked.by))
            state.keep(adopting).fold(
                { failed ->
                    logWarn("the adoption of pet ${asked.id.value} could not be recorded: $failed")
                    stay().also { asked.reply(NotRecorded(asked.id.value).left()) }
                },
                { next -> become(next).also { asked.reply(adopting.right()) } },
            )
        }
    }
}

/** A retried confirm can find its own write already done; anything else here is a bug in [Adoption]. */
private fun confirm(state: State, asked: Confirm): Next<State> {
    val pet = state.pets[asked.id] ?: return invalid("confirm", asked.id, null, asked.reply)
    return when (pet.standing) {
        Adopting(asked.by) -> {
            val adopted = pet.copy(standing = Adopted(asked.by))
            transition(asked.reply, adopted) { state.record(adopted) { seq -> PetAdopted(seq, adopted, asked.by) } }
        }

        Adopted(asked.by) -> stay().also { asked.reply(pet.right()) }

        else -> invalid("confirm", asked.id, pet, asked.reply)
    }
}

/** The pet goes back with no event: the bus never heard this adoption begin. */
private fun returned(state: State, asked: Return): Next<State> {
    val pet = state.pets[asked.id] ?: return invalid("return", asked.id, null, asked.reply)
    return when (pet.standing) {
        Adopting(asked.by) -> {
            val back = pet.copy(standing = OnShelf)
            transition(asked.reply, back) { state.keep(back) }
        }

        Adopted(asked.by) -> invalid("return", asked.id, pet, asked.reply)

        // Already back, or since taken by someone else: this return's work is done.
        else -> stay().also { asked.reply(pet.right()) }
    }
}

private fun transition(
    reply: Reply<Either<Unsettled, Pet>>,
    pet: Pet,
    write: () -> Either<Throwable, State>,
): Next<State> = write().fold(
    { failed ->
        logWarn("pet ${pet.id.value} could not be recorded as ${pet.standing}: $failed")
        stay().also { reply(Unsettled.Unrecorded.left()) }
    },
    { next -> become(next).also { reply(pet.right()) } },
)

private fun invalid(what: String, id: PetId, pet: Pet?, reply: Reply<Either<Unsettled, Pet>>): Next<State> {
    logError("a $what for pet ${id.value} found it ${pet?.standing ?: "missing"}")
    return stay().also { reply(Unsettled.Invalid(pet?.standing).left()) }
}

private fun recorded(what: String, write: () -> Either<Throwable, State>): Next<State> = write().fold(
    { failed -> stay().also { logWarn("$what could not be recorded: $failed") } },
    { next -> become(next) },
)
