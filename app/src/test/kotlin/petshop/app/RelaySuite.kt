package petshop.app

import io.github.matthewjones372.surefold.TestClock
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.left
import io.github.matthewjones372.surefold.fail.right
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeEmpty
import io.github.matthewjones372.surefold.junit.story
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.Run
import io.github.matthewjones372.surefold.stream.Running
import io.github.matthewjones372.surefold.stream.TestStreams
import io.github.matthewjones372.surefold.stream.start
import petshop.domain.Adopted
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.ShopEvent
import petshop.domain.Species
import java.sql.SQLTransientConnectionException
import kotlin.time.Duration.Companion.milliseconds

/** [claiming] runs before each claim, so a test can make one throw. */
private class Recorded(
    vararg events: ShopEvent,
    private val carried: Map<String, String> = emptyMap(),
    private val claiming: () -> Unit = {},
) : Outbox {

    private val waiting = events.toMutableList()

    val left: List<ShopEvent> get() = synchronized(waiting) { waiting.toList() }

    fun record(event: ShopEvent) = synchronized(waiting) { waiting += event }

    override fun record(pet: Pet, numbered: (seq: Long) -> ShopEvent): ShopEvent =
        synchronized(waiting) { numbered(waiting.size + 1L).also { waiting += it } }

    override fun keep(pet: Pet) = Unit

    override fun shelf(opening: List<Pet>): List<Pet> = opening

    override fun claim(limit: Int, publish: (List<Claimed>) -> List<ShopEvent>): List<ShopEvent> {
        claiming()
        val taken = publish(synchronized(waiting) { waiting.take(limit).map { Claimed(it, carried) } })
        synchronized(waiting) { waiting.removeAll { event -> taken.any { it.seq == event.seq } } }
        return taken
    }
}

private class Taking(private val refuses: (ShopEvent) -> Boolean = { false }) : EventBus {

    private val taken = mutableListOf<Long>()
    private val turnedAway = mutableListOf<Long>()
    private val inTraces = mutableListOf<String>()

    val traces: List<String> get() = synchronized(inTraces) { inTraces.toList() }

    val took: List<Long> get() = synchronized(taken) { taken.toList() }
    val refused: List<Long> get() = synchronized(turnedAway) { turnedAway.toList() }

    override fun publish(event: ShopEvent): Either<BusRefused, ShopEvent> {
        synchronized(inTraces) { inTraces += currentTrace }
        return if (refuses(event)) {
            synchronized(turnedAway) { turnedAway += event.seq }
            BusRefused(event.seq, "full").left()
        } else {
            synchronized(taken) { taken += event.seq }
            event.right()
        }
    }

    override fun consume(each: (ShopEvent) -> Unit): Run<Nothing, Long> = error("nothing reads the bus in these tests")
}

private val nibbles = Pet(PetId(1), "Nibbles", Species.Tortoise)
private val arrived = PetArrived(seq = 1, pet = nibbles)
private val adopted = PetAdopted(seq = 2, pet = nibbles.copy(standing = Adopted("Ada")), by = "Ada")
private val barnabyArrived = PetArrived(seq = 3, pet = Pet(PetId(2), "Barnaby", Species.Dog))

private val every = 50.milliseconds

private fun arrival(seq: Long) = PetArrived(seq = seq, pet = Pet(PetId(seq), "Pet ", Species.Cat))

/** A relay on [clock], ticking [every]; each `adjust` returns once every tick due by then has run. */
private fun relaying(clock: TestClock, outbox: Outbox, bus: EventBus) = relay(
    every,
    outbox,
    bus,
).start(TestStreams(clock))

private val Running<Nothing, Long>.stopped: Exit<Nothing, Long>
    get() {
        close()
        return exit.toCompletableFuture().join()
    }

object RelaySuite : Suite() {
    init {
        test("nothing leaves before the first tick, and each tick carries what was recorded, in order") {
            val clock = TestClock()
            story {
                val outbox = Given("Nibbles' arrival and adoption in the outbox") { Recorded(arrived, adopted) }
                val bus = Taking()
                val relay = And("a relay publishing to a bus") { relaying(clock, outbox, bus) }

                When("the clock moves to just before the first tick") { clock.adjust(every - 1.milliseconds) }
                Then("nothing has left: the first tick is one interval in") { bus.took.shouldBeEmpty() }

                When("it reaches the first tick") { clock.adjust(1.milliseconds) }
                Then("both events went, in order, and left the outbox") {
                    bus.took shouldBe listOf(1L, 2L)
                    outbox.left.shouldBeEmpty()
                }

                When("Barnaby arrives, and the clock moves one tick") {
                    outbox.record(barnabyArrived)
                    clock.adjust(every)
                }
                Then("his arrival went on that tick") { bus.took shouldBe listOf(1L, 2L, 3L) }
                And(
                    "stopped, the relay answers with how many it published",
                ) { relay.stopped shouldBe Exit.Done(3L) }
            }
        }

        test("an event is published inside the trace it was recorded in") {
            val clock = TestClock()
            story {
                val outbox = Given("Nibbles' arrival, recorded inside a trace") {
                    Recorded(arrived, carried = mapOf("traceparent" to "00-$A_TRACE-00f067aa0ba902b7-01"))
                }
                val bus = Taking()
                relaying(clock, outbox, bus).use { _ ->
                    When("the relay ticks") { clock.adjust(every) }
                    Then("the bus was handed it inside that trace") { bus.traces shouldBe listOf(A_TRACE) }
                }
            }
        }

        test("an event the bus refused stays in the outbox, and each refusal costs it exactly one tick") {
            val clock = TestClock()
            story {
                val refusals = java.util.concurrent.atomic.AtomicInteger(3)
                val outbox = Given("Nibbles' arrival in the outbox") { Recorded(arrived) }
                val bus = And(
                    "a bus that refuses the first three offers",
                ) { Taking(refuses = { refusals.getAndDecrement() > 0 }) }
                val relay = relaying(clock, outbox, bus)

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
        }

        test("an event behind a refused one goes on the same tick, and the refused one on the next") {
            val clock = TestClock()
            story {
                val refusedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
                val outbox = Given("Nibbles' arrival and adoption in the outbox") { Recorded(arrived, adopted) }
                val bus = And("a bus that refuses the arrival once") {
                    Taking(refuses = { event -> event.seq == 1L && refusedOnce.compareAndSet(false, true) })
                }
                val relay = relaying(clock, outbox, bus)

                When("one tick passes") { clock.adjust(every) }
                Then(
                    "the adoption went, and the refused arrival stayed: " +
                        "at least once, in order unless the bus turns one away",
                ) {
                    bus.took shouldBe listOf(2L)
                    outbox.left shouldBe listOf(arrived)
                }

                When("another tick passes") { clock.adjust(every) }
                Then("the arrival went on it") { bus.took shouldBe listOf(2L, 1L) }
                And("the relay published two") { relay.stopped shouldBe Exit.Done(2L) }
            }
        }

        test("a claim that throws restarts the relay one interval later, and nothing recorded is lost") {
            val clock = TestClock()
            story {
                val asks = java.util.concurrent.atomic.AtomicInteger()
                val outbox = Given("Nibbles' arrival, in an outbox whose first claim throws") {
                    Recorded(arrived) {
                        val first = asks.incrementAndGet() == 1
                        if (first) throw SQLTransientConnectionException("the pool had nothing to lend")
                    }
                }
                val bus = Taking()
                val relay = relaying(clock, outbox, bus)

                When("one tick passes") { clock.adjust(every) }
                Then("the first claim threw, and nothing went") {
                    asks.get() shouldBe 1
                    bus.took.shouldBeEmpty()
                }

                When("another interval passes") { clock.adjust(every) }
                Then(
                    "still nothing: restarted at two intervals in, its first tick is one interval after that",
                ) {
                    bus.took.shouldBeEmpty()
                }

                When("a third interval passes") { clock.adjust(every) }
                Then("the arrival went, and nothing recorded was lost") {
                    bus.took shouldBe listOf(1L)
                    outbox.left.shouldBeEmpty()
                }
                And("the relay published one") { relay.stopped shouldBe Exit.Done(1L) }
            }
        }

        test("a tick drains everything recorded since the last, not just one batch of it") {
            val clock = TestClock()
            story {
                val asks = java.util.concurrent.atomic.AtomicInteger()
                val outbox = Given("250 arrivals in the outbox, more than two batches") {
                    Recorded(*Array(250) { arrival(it + 1L) }) { asks.incrementAndGet() }
                }
                val bus = Taking()
                val relay = relaying(clock, outbox, bus)

                When("one tick passes") { clock.adjust(every) }
                Then("all 250 went, in order, in three claims: two full and the short one that ended the tick") {
                    bus.took shouldBe (1L..250L).toList()
                    outbox.left.shouldBeEmpty()
                    asks.get() shouldBe 3
                }
                And("the relay published 250") { relay.stopped shouldBe Exit.Done(250L) }
            }
        }

        test("a full batch the bus refuses ends the tick, so a refusing bus is retried next tick, not spun on") {
            val clock = TestClock()
            story {
                val asks = java.util.concurrent.atomic.AtomicInteger()
                val outbox = Given("150 arrivals in the outbox") {
                    Recorded(*Array(150) { arrival(it + 1L) }) { asks.incrementAndGet() }
                }
                val bus = And("a bus that refuses everything") { Taking(refuses = { true }) }
                val relay = relaying(clock, outbox, bus)

                When("two ticks pass") { clock.adjust(every * 2) }
                Then("each tick made one claim and stopped") {
                    asks.get() shouldBe 2
                    outbox.left.size shouldBe 150
                }
                And("the relay published none") { relay.stopped shouldBe Exit.Done(0L) }
            }
        }

        test("an hour of an empty outbox is one claim a tick and nothing published, without waiting the hour") {
            val clock = TestClock()
            story {
                val asks = java.util.concurrent.atomic.AtomicInteger()
                val bus = Taking()
                val relay = Given(
                    "a relay over an empty outbox that counts its claims",
                ) { relaying(clock, Recorded { asks.incrementAndGet() }, bus) }

                When("an hour passes on the test's clock") { clock.adjust(every * 72_000) }
                Then("it claimed once a tick") { asks.get() shouldBe 72_000 }
                And("published nothing") {
                    bus.took.shouldBeEmpty()
                    relay.stopped shouldBe Exit.Done(0L)
                }
            }
        }
    }
}
