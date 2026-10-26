package petshop.app

import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.overriding
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.app.subgraph
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.app.typesafe.overridingConfig
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.forAll
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.story
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.Hub
import io.github.matthewjones372.surefold.stream.start
import petshop.api.Tally
import petshop.domain.ChipRegistry
import petshop.domain.Pet
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.ShopEvent
import petshop.domain.Species
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

private class Served(val shop: PetShop, val projection: Projection)

private val served: Module =
    single { shop: PetShop, projection: Projection, _: OutboxRelay -> Served(shop, projection) }

/** A bus held to the contract; a name keeps topics and readers apart on a shared broker. */
interface BusUnderContract {

    /** The bus and what it holds open. */
    fun bus(name: String): Module

    /** The `petshop.bus` section that puts the whole service on this bus. */
    fun busConfig(name: String): String

    val remembersReaders: Boolean get() = false

    fun handled(name: String): Long? = null
}

/** [block] against the bus, in a graph that closes what the bus holds once it returns. */
internal fun <A> using(bus: Module, block: (EventBus) -> A): A =
    testApp(bus.subgraph<EventBus>()) { theBus: EventBus -> block(theBus) }

/** The contract's tests, registered through a suite's `test`, each on the bus [under] makes of the suite's resource. */
fun <R> eventBusContract(test: (String, (R) -> Unit) -> Unit, under: (R) -> BusUnderContract) {
    readers(test, under)
    service(test, under)
}

private fun <R> readers(test: (String, (R) -> Unit) -> Unit, under: (R) -> BusUnderContract) {
    test("an event published before anyone reads it reaches the first reader") { shared ->
        story {
            using(under(shared).bus("early")) { bus ->
                val waffle = arrival(1, "Waffle")
                Given("Waffle's arrival, published before anyone reads the bus") {
                    expect(bus.publish(waffle).isRight())
                }
                val seen = ConcurrentLinkedQueue<ShopEvent>()
                When("a reader starts") { bus.consume { seen.add(it) }.start(Forks()) }.use { _ ->
                    Then("it reads Waffle's arrival").eventually(30.seconds) { seen.toList() shouldBe listOf(waffle) }
                }
            }
        }
    }

    test("a reader reads events in the order they were published") { shared ->
        story {
            using(under(shared).bus("ordered")) { bus ->
                val seen = ConcurrentLinkedQueue<ShopEvent>()
                Given("a reader") { bus.consume { seen.add(it) }.start(Forks()) }.use { _ ->
                    val published = listOf(arrival(1, "Pickle"), arrival(2, "Waffle"), arrival(3, "Biscuit"))
                    When("three arrivals are published") {
                        published.forAll { expect(bus.publish(it).isRight()) }
                    }
                    Then("it reads them in that order").eventually(30.seconds) { seen.toList() shouldBe published }
                }
            }
        }
    }

    test("a reader handles an event inside the trace it was published in") { shared ->
        story {
            using(under(shared).bus("traced")) { bus ->
                val traces = ConcurrentLinkedQueue<String>()
                Given("a reader noting the trace it handles each event in") {
                    bus.consume { traces.add(currentTrace) }.start(Forks())
                }.use { _ ->
                    When("Waffle's arrival is published inside a trace") {
                        inATrace { expect(bus.publish(arrival(1, "Waffle")).isRight()) }
                    }
                    Then("the reader handled it in that trace").eventually(30.seconds) {
                        traces.toList() shouldBe listOf(A_TRACE)
                    }
                }
            }
        }
    }
}

private fun <R> service(test: (String, (R) -> Unit) -> Unit, under: (R) -> BusUnderContract) {
    test("the whole service runs on it, and an adoption reaches the projection") { shared ->
        val bus = under(shared)
        story {
            val service = Given("the whole service with its events on this bus") {
                (Petshop.module.overriding(single<ChipRegistry> { FakeRegistry() }).onAFreshDatabase() + served)
                    .subgraph<Served>()
                    .overridingConfig(bus.busConfig("service") + "\npetshop.outboxEvery = 50ms")
            }
            testApp(service) { app: Served ->
                When("Ada adopts Nibbles") { app.shop.adopt(PetId(1), by = "Ada") }
                val tally = Then("the projection counts a tortoise adopted").eventually(30.seconds) {
                    app.projection.tally().also { it.adopted(Species.Tortoise) shouldBe 1 }
                }
                And("it counted one event, once") {
                    tally.events shouldBe 1
                    tally.duplicates shouldBe 0
                }
                if (bus.remembersReaders) {
                    And("the bus remembers the reader handled it").eventually(30.seconds) {
                        bus.handled("service") shouldBe 1L
                    }
                }
            }
        }
    }
}

private fun arrival(seq: Long, name: String) = PetArrived(seq, Pet(PetId(100 + seq), name, Species.Dog))

private fun Tally.adopted(species: Species): Int = bySpecies.single { it.species == species }.adopted

private object HubUnderContract : BusUnderContract {

    override fun bus(name: String): Module = hubBus()

    override fun busConfig(name: String): String = "petshop.bus.kind = in-process"
}

object HubBusSuite : Suite() {
    init {
        eventBusContract<Unit>({ name, body -> test(name) { body(Unit) } }) { HubUnderContract }

        test("a closed bus turns an event away, and says so") {
            story {
                val bus = Given("a bus whose hub has closed") { HubBus(Hub<Claimed>(1).apply { close() }) }
                val answer = When(
                    "an arrival is published to it",
                ) { bus.publish(PetArrived(1, Pet(PetId(9), "Waffle", Species.Dog))) }
                Then("it is refused, so the outbox keeps it") { answer.isLeft() shouldBe true }
            }
        }
    }
}
