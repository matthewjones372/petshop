package petshop.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.TestStreams
import io.github.matthewjones372.lark.stream.start
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.PetReturned
import petshop.domain.ShopEvent
import petshop.domain.Species
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The shop's side of the relay: the events it has not sent, oldest first, and a way to mark one sent. */
private class Recorded(vararg events: ShopEvent) {

    private val waiting = events.toMutableList()

    val left: List<ShopEvent> get() = synchronized(waiting) { waiting.toList() }

    fun record(event: ShopEvent) = synchronized(waiting) { waiting += event }

    fun unsent(): List<ShopEvent> = synchronized(waiting) { waiting.toList() }

    fun sent(event: ShopEvent) = synchronized(waiting) { waiting.removeIf { it.seq == event.seq } }
}

/** A bus that keeps what it took, and turns an event away while [refuses] says so. */
private class Taking(private val refuses: (ShopEvent) -> Boolean = { false }) : EventBus {

    private val taken = mutableListOf<Long>()
    private val turnedAway = mutableListOf<Long>()

    val took: List<Long> get() = synchronized(taken) { taken.toList() }
    val refused: List<Long> get() = synchronized(turnedAway) { turnedAway.toList() }

    override fun publish(event: ShopEvent): Either<BusRefused, ShopEvent> =
        if (refuses(event)) {
            synchronized(turnedAway) { turnedAway += event.seq }
            BusRefused(event.seq, "full").left()
        } else {
            synchronized(taken) { taken += event.seq }
            event.right()
        }

    override fun subscribe(): Stream<Nothing, ShopEvent> = error("nothing reads the bus in these tests")
}

private val nibbles = Pet(PetId(1), "Nibbles", Species.Tortoise)
private val arrived = PetArrived(seq = 1, pet = nibbles)
private val adopted = PetAdopted(seq = 2, pet = nibbles.copy(adopted = true), by = "Ada")
private val returned = PetReturned(seq = 3, pet = nibbles)

/**
 * The relay on a clock the test owns. Each `adjust` returns once every tick due by then has run, so a
 * test says which tick an event went on, where EventsSpec, on Pekko's clock, can only wait for it.
 */
class RelaySpec {

    private val every = 50.milliseconds
    private val clock = TestClock()

    private fun relaying(outbox: Recorded, bus: EventBus, unsent: () -> List<ShopEvent> = outbox::unsent) =
        relay(every, unsent, bus, outbox::sent).start(TestStreams(clock))

    private val Running<Nothing, Long>.stopped: Exit<Nothing, Long>
        get() {
            close()
            return exit.toCompletableFuture().join()
        }

    @Test
    fun `nothing leaves before the first tick, and each tick carries what was recorded, in order`() {
        val outbox = Recorded(arrived, adopted)
        val bus = Taking()
        val relay = relaying(outbox, bus)

        clock.adjust(every - 1.milliseconds)
        withClue("the first tick is one interval in") { bus.took.shouldBeEmpty() }

        clock.adjust(1.milliseconds)
        bus.took shouldBe listOf(1L, 2L)
        outbox.left.shouldBeEmpty()

        outbox.record(returned)
        clock.adjust(every)
        bus.took shouldBe listOf(1L, 2L, 3L)

        withClue("stopped, the relay answers with how many it published") { relay.stopped shouldBe Exit.Done(3L) }
    }

    @Test
    fun `an event the bus refused stays in the outbox, and each refusal costs it exactly one tick`() {
        val refusals = java.util.concurrent.atomic.AtomicInteger(3)
        val outbox = Recorded(arrived)
        val bus = Taking(refuses = { refusals.getAndDecrement() > 0 })
        val relay = relaying(outbox, bus)

        clock.adjust(every * 3)
        withClue("three ticks, three refusals, and the event is where it was") {
            bus.refused shouldBe listOf(1L, 1L, 1L)
            bus.took.shouldBeEmpty()
            outbox.left shouldBe listOf(arrived)
        }

        clock.adjust(every)
        bus.took shouldBe listOf(1L)
        outbox.left.shouldBeEmpty()
        relay.stopped shouldBe Exit.Done(1L)
    }

    @Test
    fun `an event behind a refused one goes on the same tick, and the refused one on the next`() {
        val refusedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val outbox = Recorded(arrived, adopted)
        val bus = Taking(refuses = { event -> event.seq == 1L && refusedOnce.compareAndSet(false, true) })
        val relay = relaying(outbox, bus)

        clock.adjust(every)
        withClue("at least once, in order unless the bus turns one away") {
            bus.took shouldBe listOf(2L)
            outbox.left shouldBe listOf(arrived)
        }

        clock.adjust(every)
        bus.took shouldBe listOf(2L, 1L)
        relay.stopped shouldBe Exit.Done(2L)
    }

    @Test
    fun `a timed-out ask restarts the relay one interval later, and nothing recorded is lost`() {
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val outbox = Recorded(arrived)
        val bus = Taking()
        val relay = relaying(outbox, bus, unsent = {
            if (asks.incrementAndGet() == 1) throw TimeoutException("the shop did not answer")
            outbox.unsent()
        })

        clock.adjust(every)
        withClue("the first ask timed out, and the restart waits one interval") {
            asks.get() shouldBe 1
            bus.took.shouldBeEmpty()
        }

        clock.adjust(every)
        withClue("restarted at two intervals in, its first tick is one interval after that") {
            bus.took.shouldBeEmpty()
        }

        clock.adjust(every)
        bus.took shouldBe listOf(1L)
        outbox.left.shouldBeEmpty()
        relay.stopped shouldBe Exit.Done(1L)
    }

    @Test
    fun `an hour of an empty outbox is one ask a tick and nothing published, without waiting the hour`() {
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val bus = Taking()
        val relay = relaying(Recorded(), bus, unsent = { asks.incrementAndGet(); emptyList() })

        clock.adjust(every * 72_000)

        asks.get() shouldBe 72_000
        bus.took.shouldBeEmpty()
        relay.stopped shouldBe Exit.Done(0L)
    }
}
