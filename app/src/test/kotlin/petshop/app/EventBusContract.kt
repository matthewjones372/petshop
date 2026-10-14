package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.Hub
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.test.story
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import petshop.api.Tally
import petshop.domain.ChipRegistry
import petshop.domain.Pet
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.ShopEvent
import petshop.domain.Species

private class Served(val shop: PetShop, val projection: Projection)

private val served: Module =
    single { shop: PetShop, projection: Projection, _: OutboxRelay -> Served(shop, projection) }

abstract class EventBusContract {

    /** The bus and what it holds open; [name] keeps topics and readers apart on a shared broker. */
    abstract fun bus(name: String): Module

    /** The `petshop.bus` section that puts the whole service on this bus. */
    abstract fun busConfig(name: String): String

    /** [block] against the bus, in a graph that closes what the bus holds once it returns. */
    protected fun <A> using(bus: Module, block: (EventBus) -> A): A =
        testApp(bus.subgraph<EventBus>()) { theBus: EventBus -> block(theBus) }

    protected open val remembersReaders: Boolean = false

    protected open fun handled(name: String): Long? = null

    @Test
    fun `an event published before anyone reads it reaches the first reader`() = story {
        using(bus("early")) { bus ->
            val waffle = Given("Waffle's arrival, published before anyone reads the bus") {
                arrival(1, "Waffle").also { bus.publish(it).shouldBeRight() }
            }
            val seen = ConcurrentLinkedQueue<ShopEvent>()
            When("a reader starts") { bus.consume { seen.add(it) }.start(Forks()) }.use { _ ->
                Then("it reads Waffle's arrival").eventually(30.seconds) { seen.toList() shouldBe listOf(waffle) }
            }
        }
    }

    @Test
    fun `a reader reads events in the order they were published`() = story {
        using(bus("ordered")) { bus ->
            val seen = ConcurrentLinkedQueue<ShopEvent>()
            Given("a reader") { bus.consume { seen.add(it) }.start(Forks()) }.use { _ ->
                val published = When("three arrivals are published") {
                    listOf(arrival(1, "Pickle"), arrival(2, "Waffle"), arrival(3, "Biscuit"))
                        .onEach { bus.publish(it).shouldBeRight() }
                }
                Then("it reads them in that order").eventually(30.seconds) { seen.toList() shouldBe published }
            }
        }
    }

    @Test
    fun `the whole service runs on it, and an adoption reaches the projection`() = story {
        val service = Given("the whole service with its events on this bus") {
            (Petshop.module.overriding(single<ChipRegistry> { FakeRegistry() }).onAFreshDatabase() + served)
                .subgraph<Served>()
                .overridingConfig(busConfig("service") + "\npetshop.outboxEvery = 50ms")
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
            if (remembersReaders) {
                And("the bus remembers the reader handled it").eventually(30.seconds) { handled("service") shouldBe 1L }
            }
        }
    }

    private fun arrival(seq: Long, name: String) = PetArrived(seq, Pet(PetId(100 + seq), name, Species.Dog))

    private fun Tally.adopted(species: Species): Int = bySpecies.single { it.species == species }.adopted
}

class HubBusSpec : EventBusContract() {

    override fun bus(name: String): Module = hubBus()

    override fun busConfig(name: String): String = "petshop.bus.kind = in-process"

    @Test
    fun `a closed bus turns an event away, and says so`() = story {
        val bus = Given("a bus whose hub has closed") { HubBus(Hub<ShopEvent>(1).apply { close() }) }
        val answer = When("an arrival is published to it") { bus.publish(PetArrived(1, Pet(PetId(9), "Waffle", Species.Dog))) }
        Then("it is refused, so the outbox keeps it") { answer.isLeft() shouldBe true }
    }
}
