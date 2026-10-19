package petshop.app

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.either
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.VirtualThreads
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.logError
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.retryRaise
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.blocking
import io.github.matthewjones372.lark.stream.flatMapMerge
import io.github.matthewjones372.lark.stream.fromStage
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.lark.stream.start
import petshop.domain.Adopting
import petshop.domain.ChipRegistry
import petshop.domain.NotChipped
import petshop.domain.NotRegistered
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShopError
import petshop.domain.RegistryDown
import petshop.domain.RegistryError
import petshop.domain.Unreachable
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
            Stream.fromStage(CompletableFuture.supplyAsync({ settle(unfinished) }, VirtualThreads))
        }
        .runFold(0L) { settled, _ -> settled + 1 }
        .start(streams)

    init {
        // Nothing is in flight at start-up, so every pet still being adopted was left by a run that stopped.
        window.pets().forEach { pet -> (pet.standing as? Adopting)?.let { waiting.put(Unfinished(pet.id, it.by)) } }
    }

    /** The actor decides first, so a lost race never calls the registry. */
    fun adopt(id: PetId, by: String): AdoptionReport {
        val attempt = Attempt(Unfinished(id, by))
        val answer = either {
            val adopting = ref.asked { reply -> Adopt(id, by, reply) }.bind()
            val chip = registry.lookup(id).getOrElse { failure ->
                attempt.warn("the registry could not look up pet ${id.value}: $failure")
                refuse(attempt, failure.refusing(id))
            }
            registry.transfer(chip, to = by).fold(
                { failure ->
                    attempt.warn("the registry did not record $by keeping pet ${id.value}: $failure")
                    when (failure) {
                        NotRegistered -> refuse(attempt, NotChipped(id.value))
                        is Unreachable -> readBack(attempt, adopting)
                    }
                },
                { confirm(attempt, adopting) },
            )
        }
        return AdoptionReport(answer, attempt.warnings)
    }

    /** The transfer may have landed before its answer was lost, so the registry is asked who keeps the pet. */
    private fun Raise<PetShopError>.readBack(attempt: Attempt, adopting: Pet): Pet {
        val (id, by) = attempt.adoption
        return registry.lookup(id).fold(
            { failure ->
                adopting.also { settleLater(attempt, "the keeper of pet ${id.value} was not read back: $failure") }
            },
            { chip -> if (chip.keeper == by) confirm(attempt, adopting) else refuse(attempt, RegistryDown(id.value)) },
        )
    }

    private fun confirm(attempt: Attempt, adopting: Pet): Pet {
        val (id, by) = attempt.adoption
        return ref.settled { reply -> Confirm(id, by, reply) }.getOrElse { failure ->
            adopting.also { settleLater(attempt, "the adoption of pet ${id.value} was not confirmed: $failure") }
        }
    }

    private fun Raise<PetShopError>.refuse(attempt: Attempt, why: PetShopError): Nothing {
        val (id, by) = attempt.adoption
        ref.settled { reply -> Return(id, by, reply) }
            .onLeft { failure -> settleLater(attempt, "the return of pet ${id.value} was not recorded: $failure") }
        raise(why)
    }

    private fun settleLater(attempt: Attempt, warning: String) {
        attempt.warn("$warning; settling it later")
        waiting.put(attempt.adoption)
    }

    /** Done once the registry has answered and the actor has written what it said; only a bug or a close gives up. */
    private fun settle(unfinished: Unfinished) {
        val (id, by) = unfinished
        Schedule.exponential<Unsettled>(settleEvery)
            .delayed { _, wait -> minOf(wait, 30.seconds) }
            .doWhile { failure, _ -> failure !is Unsettled.Invalid && !closed.get() }
            .retryRaise {
                val kept = registry.lookup(id).fold(
                    { failure -> if (failure == NotRegistered) false else raise(Unsettled.Unrecorded) },
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

    private class Attempt(val adoption: Unfinished) {
        val warnings = mutableListOf<String>()

        fun warn(warning: String) {
            warnings += warning
        }
    }
}

/** Enough for every pet a shop holds at once; a pet past it waits for one to settle. */
private const val SETTLING_AT_ONCE = 256

private fun RegistryError.refusing(id: PetId): PetShopError = when (this) {
    NotRegistered -> NotChipped(id.value)
    is Unreachable -> RegistryDown(id.value)
}

/** Throws when the actor stopped or timed out: that is a fault (a 500), not a declared refusal. */
private fun <A : Any> ActorRef<Shop>.asked(message: (Reply<A>) -> Shop): A =
    ask(within = 3.seconds, message).getOrElse { failure -> error("the shop did not answer: $failure") }

/** An actor that does not answer leaves the pet as unsettled as a write that failed. */
private fun ActorRef<Shop>.settled(message: (Reply<Either<Unsettled, Pet>>) -> Shop): Either<Unsettled, Pet> =
    ask(within = 3.seconds, message).getOrElse { Unsettled.Unrecorded.left() }
