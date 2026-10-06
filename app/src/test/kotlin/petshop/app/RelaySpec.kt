package petshop.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.Run
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.TestStreams
import io.github.matthewjones372.lark.stream.start
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
import java.sql.SQLTransientConnectionException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An outbox in a list, for a test about what the relay does on each tick rather than about the table:
 * PostgresOutboxSpec is about the table. [claiming] runs before each claim, so a test can make one
 * throw.
 */
private class Recorded(vararg events: ShopEvent, private val claiming: () -> Unit = {}) : Outbox {

    private val waiting = events.toMutableList()

    val left: List<ShopEvent> get() = synchronized(waiting) { waiting.toList() }

    fun record(event: ShopEvent) = synchronized(waiting) { waiting += event }

    override fun record(pet: Pet, numbered: (seq: Long) -> ShopEvent): ShopEvent =
        synchronized(waiting) { numbered(waiting.size + 1L).also { waiting += it } }

    override fun shelf(opening: List<Pet>): List<Pet> = opening

    override fun claim(limit: Int, publish: (List<ShopEvent>) -> List<ShopEvent>): List<ShopEvent> {
        claiming()
        val taken = publish(synchronized(waiting) { waiting.take(limit) })
        synchronized(waiting) { waiting.removeAll { event -> taken.any { it.seq == event.seq } } }
        return taken
    }
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

    override fun consume(each: (ShopEvent) -> Unit): Run<Nothing, Long> = error("nothing reads the bus in these tests")

    override fun close() = Unit
}

private val nibbles = Pet(PetId(1), "Nibbles", Species.Tortoise)
private val arrived = PetArrived(seq = 1, pet = nibbles)
private val adopted = PetAdopted(seq = 2, pet = nibbles.copy(adopted = true), by = "Ada")
private val returned = PetReturned(seq = 3, pet = nibbles)

/**
 * The relay on a clock the test owns. Each `adjust` returns once every tick due by then has run, so a
 * test says which tick an event went on, where EventsSpec, on the wall clock, can only wait for it.
 */
class RelaySpec {

    private val every = 50.milliseconds
    private val clock = TestClock()

    private fun relaying(outbox: Outbox, bus: EventBus) = relay(every, outbox, bus).start(TestStreams(clock))

    private val Running<Nothing, Long>.stopped: Exit<Nothing, Long>
        get() {
            close()
            return exit.toCompletableFuture().join()
        }

    @Test
    fun `nothing leaves before the first tick, and each tick carries what was recorded, in order`() = story {
        val outbox = Given("Nibbles' arrival and adoption in the outbox") { Recorded(arrived, adopted) }
        val bus = Taking()
        val relay = And("a relay publishing to a bus") { relaying(outbox, bus) }

        When("the clock moves to just before the first tick") { clock.adjust(every - 1.milliseconds) }
        Then("nothing has left: the first tick is one interval in") { bus.took.shouldBeEmpty() }

        When("it reaches the first tick") { clock.adjust(1.milliseconds) }
        Then("both events went, in order, and left the outbox") {
            bus.took shouldBe listOf(1L, 2L)
            outbox.left.shouldBeEmpty()
        }

        When("Nibbles is returned, and the clock moves one tick") {
            outbox.record(returned)
            clock.adjust(every)
        }
        Then("the return went on that tick") { bus.took shouldBe listOf(1L, 2L, 3L) }
        And("stopped, the relay answers with how many it published") { relay.stopped shouldBe Exit.Done(3L) }
    }

    @Test
    fun `an event the bus refused stays in the outbox, and each refusal costs it exactly one tick`() = story {
        val refusals = java.util.concurrent.atomic.AtomicInteger(3)
        val outbox = Given("Nibbles' arrival in the outbox") { Recorded(arrived) }
        val bus = And("a bus that refuses the first three offers") { Taking(refuses = { refusals.getAndDecrement() > 0 }) }
        val relay = relaying(outbox, bus)

        When("three ticks pass") { clock.adjust(every * 3) }
        Then("there were three refusals, and the event is where it was") {
            bus.refused shouldBe listOf(1L, 1L, 1L)
            bus.took.shouldBeEmpty()
            outbox.left shouldBe listOf(arrived)
        }

        When("a fourth tick passes") { clock.adjust(every) }
        Then("the bus took it, and the outbox is empty") {
            bus.took shouldBe listOf(1L)
            outbox.left.shouldBeEmpty()
        }
        And("the relay published one") { relay.stopped shouldBe Exit.Done(1L) }
    }

    @Test
    fun `an event behind a refused one goes on the same tick, and the refused one on the next`() = story {
        val refusedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val outbox = Given("Nibbles' arrival and adoption in the outbox") { Recorded(arrived, adopted) }
        val bus = And("a bus that refuses the arrival once") {
            Taking(refuses = { event -> event.seq == 1L && refusedOnce.compareAndSet(false, true) })
        }
        val relay = relaying(outbox, bus)

        When("one tick passes") { clock.adjust(every) }
        Then("the adoption went, and the refused arrival stayed: at least once, in order unless the bus turns one away") {
            bus.took shouldBe listOf(2L)
            outbox.left shouldBe listOf(arrived)
        }

        When("another tick passes") { clock.adjust(every) }
        Then("the arrival went on it") { bus.took shouldBe listOf(2L, 1L) }
        And("the relay published two") { relay.stopped shouldBe Exit.Done(2L) }
    }

    @Test
    fun `a claim that throws restarts the relay one interval later, and nothing recorded is lost`() = story {
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val outbox = Given("Nibbles' arrival, in an outbox whose first claim throws") {
            Recorded(arrived) {
                if (asks.incrementAndGet() == 1) throw SQLTransientConnectionException("the pool had nothing to lend")
            }
        }
        val bus = Taking()
        val relay = relaying(outbox, bus)

        When("one tick passes") { clock.adjust(every) }
        Then("the first claim threw, and nothing went") {
            asks.get() shouldBe 1
            bus.took.shouldBeEmpty()
        }

        When("another interval passes") { clock.adjust(every) }
        Then("still nothing: restarted at two intervals in, its first tick is one interval after that") {
            bus.took.shouldBeEmpty()
        }

        When("a third interval passes") { clock.adjust(every) }
        Then("the arrival went, and nothing recorded was lost") {
            bus.took shouldBe listOf(1L)
            outbox.left.shouldBeEmpty()
        }
        And("the relay published one") { relay.stopped shouldBe Exit.Done(1L) }
    }

    @Test
    fun `an hour of an empty outbox is one claim a tick and nothing published, without waiting the hour`() = story {
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val bus = Taking()
        val relay = Given("a relay over an empty outbox that counts its claims") { relaying(Recorded { asks.incrementAndGet() }, bus) }

        When("an hour passes on the test's clock") { clock.adjust(every * 72_000) }
        Then("it claimed once a tick") { asks.get() shouldBe 72_000 }
        And("published nothing") {
            bus.took.shouldBeEmpty()
            relay.stopped shouldBe Exit.Done(0L)
        }
    }
}
