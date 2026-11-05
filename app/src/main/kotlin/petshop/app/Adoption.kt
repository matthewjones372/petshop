package petshop.app

import dev.surefold.Schedule
import dev.surefold.Settled
import dev.surefold.VirtualThreads
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.fail
import dev.surefold.fail.fold
import dev.surefold.fail.getOrElse
import dev.surefold.fail.left
import dev.surefold.fail.onLeft
import dev.surefold.logError
import dev.surefold.logInfo
import dev.surefold.retrying
import dev.surefold.saga
import dev.surefold.stream.Running
import dev.surefold.stream.Stream
import dev.surefold.stream.StreamBackend
import dev.surefold.stream.blocking
import dev.surefold.stream.flatMapMerge
import dev.surefold.stream.fromStage
import dev.surefold.stream.runFold
import dev.surefold.stream.start
import petshop.domain.Adopting
import petshop.domain.Chip
import petshop.domain.ChipRegistry
import petshop.domain.NotChipped
import petshop.domain.NotRegistered
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShopError
import petshop.domain.RegistryDown
import petshop.domain.RegistryError
import petshop.domain.Unreachable
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Warnings are what went wrong on the way to [answer] without changing it, for the caller to log. */
data class AdoptionReport(val answer: Either<PetShopError, Pet>, val warnings: List<String>)

/** The answer's [Pet.standing] is `Adopted` when the registry confirmed it, `Adopting` while it is being settled. */
class Adoption(
    private val ref: ActorRef<Shop>,
    private val registry: ChipRegistry,
    window: ShopWindow,
    streams: StreamBackend,
    private val settleEvery: Duration = 100.milliseconds,
) : AutoCloseable {

    private val waiting = LinkedBlockingQueue<Unfinished>()
    private val closed = AtomicBoolean(false)

    // Each pet settles on a thread of its own, so one the registry never answers about holds up no other.
    private val settling: Running<Nothing, Long> = Stream.blocking(
        open = { waiting },
        next = { queue -> queue.take().takeUnless { it === Unfinished.Stop } },
        wake = { queue -> queue.put(Unfinished.Stop) },
        close = {},
    )
        .flatMapMerge(breadth = SETTLING_AT_ONCE) { unfinished ->
            // Caught here: a throw would fail the whole stream, and no other pet would settle until a restart.
            val settled = {
                Either.catch { settle(unfinished) }
                    .onLeft { thrown -> logError("settling pet ${unfinished.id.value} threw: $thrown") }
            }
            Stream.fromStage(CompletableFuture.supplyAsync(settled, VirtualThreads))
        }
        .runFold(0L) { settled, _ -> settled + 1 }
        .start(streams)

    init {
        // The actor shows its shelf before it answers anything, so once it answers the window is the one it
        // opened with.
        ref.ask(within = 3.seconds) { reply -> Ping(reply) }
            .onLeft { failure -> logError("the shop did not answer before settling started: $failure") }
        // Nothing is in flight at start-up, so every pet still being adopted was left by a run that stopped.
        window.pets().forEach { pet -> (pet.standing as? Adopting)?.let { waiting.put(Unfinished(pet.id, it.by)) } }
    }

    /** The actor decides first, so a lost race never calls the registry. */
    fun adopt(id: PetId, by: String): AdoptionReport {
        val adoption = Unfinished(id, by)
        val (warnings, answer) = warnings {
            attempt {
                // A refusal undoes the reservation. This attempt is its own saga: the registry has no idempotency key.
                saga("${id.value}:${UUID.randomUUID()}", name = "adoption") {
                    val adopting = step(
                        name = "reserve",
                        action = { reserve(adoption) },
                        // The actor may still be writing this reservation, so another ask waits behind that write.
                        check = { Settled.DidNot },
                        undo = { _, _, _ -> release(adoption) },
                    )
                    val chip = findChip(adoption)
                    when (handOver(adoption, chip)) {
                        HandOver.Pending -> adopting
                        HandOver.Recorded -> confirm(adoption, adopting)
                    }
                }
            }
        }
        return AdoptionReport(answer, warnings)
    }

    context(_: Fail<PetShopError>)
    private fun reserve(adoption: Unfinished): Pet {
        val (id, by) = adoption
        // Throws when the actor stopped or timed out: that is a fault (a 500), not a declared refusal.
        return ref.ask(within = 3.seconds) { reply -> Adopt(id, by, reply) }.getOrElse { failure ->
            // The actor may still record the pet as being adopted after the ask gave up. The registry was
            // never asked, so settling finds the shop still keeps the pet and puts it back on the shelf.
            waiting.put(adoption)
            error("the shop did not answer: $failure")
        }.bind()
    }

    context(_: Warnings, _: Fail<PetShopError>)
    private fun findChip(adoption: Unfinished): Chip = registry.lookup(adoption.id).getOrElse { failure ->
        warn("the registry could not look up pet ${adoption.id.value}: $failure")
        fail(failure.refusing(adoption.id))
    }

    context(_: Warnings, _: Fail<PetShopError>)
    private fun handOver(adoption: Unfinished, chip: Chip): HandOver {
        val (id, by) = adoption
        return registry.transfer(chip, to = by).fold(
            { failure ->
                warn("the registry did not record $by keeping pet ${id.value}: $failure")
                when (failure) {
                    NotRegistered -> fail(NotChipped(id.value))
                    is Unreachable -> readBack(adoption)
                }
            },
            { HandOver.Recorded },
        )
    }

    /** The transfer may have landed before its answer was lost, so the registry is asked who keeps the pet. */
    context(_: Warnings, _: Fail<PetShopError>)
    private fun readBack(adoption: Unfinished): HandOver {
        val (id, by) = adoption
        return registry.lookup(id).fold(
            { failure ->
                settleLater(adoption, "the keeper of pet ${id.value} was not read back: $failure")
                HandOver.Pending
            },
            { chip -> if (chip.keeper == by) HandOver.Recorded else fail(RegistryDown(id.value)) },
        )
    }

    context(_: Warnings)
    private fun confirm(adoption: Unfinished, adopting: Pet): Pet {
        val (id, by) = adoption
        return ref.settled { reply -> Confirm(id, by, reply) }.getOrElse { failure ->
            adopting.also { settleLater(adoption, "the adoption of pet ${id.value} was not confirmed: $failure") }
        }
    }

    // An undo that throws becomes NotUndone and hides the registry's refusal, so a failed write is settled later.
    context(_: Warnings)
    private fun release(adoption: Unfinished) {
        val (id, by) = adoption
        ref.settled { reply -> Return(id, by, reply) }
            .onLeft { failure -> settleLater(adoption, "the return of pet ${id.value} was not recorded: $failure") }
    }

    private sealed interface HandOver {
        data object Recorded : HandOver

        data object Pending : HandOver
    }

    context(_: Warnings)
    private fun settleLater(adoption: Unfinished, warning: String) {
        warn("$warning; settling it later")
        waiting.put(adoption)
    }

    /** Done once the registry has answered and the actor has written what it said; only a bug or a close gives up. */
    private fun settle(unfinished: Unfinished) {
        val (id, by) = unfinished
        Schedule.exponential<Unsettled>(settleEvery)
            .delayed { _, wait -> minOf(wait, 30.seconds) }
            .doWhile { failure, _ -> failure !is Unsettled.Invalid && !closed.get() }
            .retrying {
                val kept = registry.lookup(id).fold(
                    { failure -> if (failure == NotRegistered) false else fail(Unsettled.Unrecorded) },
                    { chip -> chip.keeper == by },
                )
                ref.settled { reply -> if (kept) Confirm(id, by, reply) else Return(id, by, reply) }.bind()
            }
            .fold(
                { failure -> logError("pet ${id.value} was left unsettled: $failure") },
                { pet -> logInfo("pet ${id.value} settled as ${pet.standing}") },
            )
    }

    override fun close() {
        closed.set(true)
        settling.close()
    }

    private data class Unfinished(val id: PetId, val by: String) {
        companion object {
            val Stop = Unfinished(PetId(-1), "")
        }
    }
}

/** The effect of noting what went wrong without changing the answer, as [Fail] is the effect of refusing. */
private fun interface Warnings {
    fun warn(warning: String)
}

context(warnings: Warnings)
private fun warn(warning: String) = warnings.warn(warning)

/** Runs [block] with a [Warnings] in scope and returns what it noted beside what it answered. */
private inline fun <A> warnings(block: context(Warnings) () -> A): Pair<List<String>, A> {
    val noted = mutableListOf<String>()
    val answer = block(Warnings { warning -> noted += warning })
    return noted.toList() to answer
}

/** Enough for every pet a shop holds at once; a pet past it waits for one to settle. */
private const val SETTLING_AT_ONCE = 256

private fun RegistryError.refusing(id: PetId): PetShopError = when (this) {
    NotRegistered -> NotChipped(id.value)
    is Unreachable -> RegistryDown(id.value)
}

/** An actor that does not answer leaves the pet as unsettled as a write that failed. */
private fun ActorRef<Shop>.settled(message: (Reply<Either<Unsettled, Pet>>) -> Shop): Either<Unsettled, Pet> =
    ask(within = 3.seconds, message).getOrElse { Unsettled.Unrecorded.left() }
