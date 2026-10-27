package petshop.app

import io.github.matthewjones372.surefold.actor.ActorRef
import io.github.matthewjones372.surefold.actor.AskFailure
import io.github.matthewjones372.surefold.actor.Behaviour
import io.github.matthewjones372.surefold.actor.Next
import io.github.matthewjones372.surefold.actor.Reply
import io.github.matthewjones372.surefold.actor.ask
import io.github.matthewjones372.surefold.actor.become
import io.github.matthewjones372.surefold.actor.behaviour
import io.github.matthewjones372.surefold.actor.onStart
import io.github.matthewjones372.surefold.actor.stay
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.fold
import io.github.matthewjones372.surefold.fail.left
import io.github.matthewjones372.surefold.fail.map
import io.github.matthewjones372.surefold.fail.right
import io.github.matthewjones372.surefold.logError
import io.github.matthewjones372.surefold.logWarn
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
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * A shop actor is the only writer of its pets: one message at a time, so of two racing adopters the second is refused.
 * With several shards each pet belongs to exactly one of them, chosen by [shardOf], so that still holds per pet.
 */
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

/**
 * Which of [shards] shop actors owns the pet [id]. The id is mixed first: by `id mod shards` alone every pet of a shard
 * shares its low bits, which are the ones a HashMap picks its bucket by, so a shard's pets piled into a few buckets.
 */
fun shardOf(id: PetId, shards: Int): Int = Math.floorMod((id.value * FIBONACCI ushr Int.SIZE_BITS).toInt(), shards)

/** 2^64 divided by the golden ratio, odd: multiplying by it spreads consecutive ids over the high bits. */
private const val FIBONACCI = -0x61c8864680b583ebL

/** The pet a message is about, so it reaches the shard that owns it; a [Ping] has none. */
internal fun Shop.petId(): PetId? = when (this) {
    is Arrived -> pet.id
    is Adopt -> id
    is Confirm -> id
    is Return -> id
    is Ping -> null
}

/**
 * What readers see of every shard's pets. A write changes one pet in place, in O(log n), and the catalogue is rebuilt
 * on the first read after a change rather than on every write, so a busy shelf costs its writers nothing per pet. A
 * read never sees less than every write that returned before it, since a write bumps [version] after its pet is in.
 */
class ShopWindow {
    private val byId = ConcurrentSkipListMap<Long, Pet>()
    private val version = AtomicLong()
    private val cached = AtomicReference(Listed(-1, Catalogue(emptyList())))

    fun pets(): List<Pet> = byId.values.toList()

    fun find(id: PetId): Pet? = byId[id.value]

    /** The same [Catalogue] until a pet changes, so its encoded bytes are reused (CatalogueBytesSuite). */
    fun listed(): List<PetDto> {
        val now = cached.get()
        if (now.version == version.get()) return now.catalogue
        // One reader rebuilds; the rest wait for it rather than each sorting the shelf again.
        return synchronized(this) {
            val seen = version.get()
            cached.get().takeIf { it.version == seen }?.catalogue
                ?: Catalogue(byId.values.toList().toDto()).also { cached.set(Listed(seen, it)) }
        }
    }

    internal fun show(pets: Map<PetId, Pet>) {
        pets.values.forEach { byId[it.id.value] = it }
        version.incrementAndGet()
    }

    internal fun put(pet: Pet) {
        byId[pet.id.value] = pet
        version.incrementAndGet()
    }
}

private class Listed(val version: Long, val catalogue: Catalogue)

/**
 * The actor's pets, changed in place: only the actor touches them, one message at a time, and a pet changes only after
 * its write. Copying the map on every write cost a shard's whole catalogue per adoption. A restart therefore carries on
 * from the pets as they now stand, which is what the table holds, rather than from the opening catalogue.
 */
private class State(pets: Map<PetId, Pet>, private val outbox: Outbox, private val window: ShopWindow) {
    private val held = HashMap(pets)

    val pets: Map<PetId, Pet> get() = held

    /** The pet's row and its event are one transaction, so the state changes only once both are durable. */
    fun record(pet: Pet, event: (seq: Long) -> ShopEvent): Either<Throwable, State> =
        Either.catch { outbox.record(pet, event) }.map { with(pet) }

    fun keep(pet: Pet): Either<Throwable, State> = Either.catch { outbox.keep(pet) }.map { with(pet) }

    private fun with(pet: Pet): State {
        held[pet.id] = pet
        window.put(pet)
        return this
    }
}

/** Every write is caught where it is made: a throw out of a step would restart the actor. */
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
