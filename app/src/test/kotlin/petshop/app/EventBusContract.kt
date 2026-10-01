package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.start
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
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

/** The shop to act on, and the projection reading the bus. The relay is a dependency so that it runs. */
private class Served(val shop: PetShop, val projection: Projection)

private val served: Module =
    single { shop: PetShop, projection: Projection, _: OutboxRelay -> Served(shop, projection) }

/**
 * What every [EventBus] promises, as tests. A bus's spec extends this and says how to make one, and adds what is
 * true of that bus alone; a new bus is one more class that passes these.
 */
abstract class EventBusContract {

    /** A new bus with nothing on it. [name] is the test's own, for a bus whose topics and readers are shared. */
    abstract fun bus(name: String): EventBus

    /** Whether the bus remembers where a reader got to, and so has [handled] to ask. */
    protected open val remembersReaders: Boolean = false

    /** How many events the reader named [name] has acknowledged handling, on a bus that [remembersReaders]. */
    protected open fun handled(name: String): Long? = null

    @Test
    fun `an event published before anyone reads it reaches the first reader`() = story {
        bus("early").use { bus ->
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
        bus("ordered").use { bus ->
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
            (
                petshop.overriding(single<ChipRegistry> { FakeRegistry() })
                    .overriding(singleOf<EventBus>({ bus("service") }, { bus -> bus.close() }))
                    .onAFreshDatabase() + served
                )
                .subgraph<Served>()
                .overridingConfig("petshop.outboxEvery = 50ms")
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

/** The in-process bus: Lark's [io.github.matthewjones372.lark.stream.Hub], and nothing to set up. */
class HubBusSpec : EventBusContract() {

    override fun bus(name: String): EventBus = HubBus()

    @Test
    fun `a closed bus turns an event away, and says so`() = story {
        val bus = Given("a bus that has closed") { HubBus().apply { close() } }
        val answer = When("an arrival is published to it") { bus.publish(PetArrived(1, Pet(PetId(9), "Waffle", Species.Dog))) }
        Then("it is refused, so the outbox keeps it") { answer.isLeft() shouldBe true }
    }
}
