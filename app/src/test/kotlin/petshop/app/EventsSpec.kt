package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.test.story
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import petshop.api.Tally
import petshop.domain.ChipRegistry
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.Species
import petshop.domain.Unreachable

/** What a test watches from: the shop to act on, the bus to publish to, the consumer to read. */
private class Observed(val shop: PetShop, val bus: EventBus, val projection: Projection)

/**
 * The relay is a dependency here so that it runs: nothing else in this subgraph reaches it, and a
 * background job nothing reaches is not started.
 */
private val observed: Module =
    single { shop: PetShop, bus: EventBus, projection: Projection, _: OutboxRelay ->
        Observed(shop, bus, projection)
    }

/**
 * The shop, the outbox table, the relay, the bus and the consumer, with [registry] at the node. No port,
 * no arrivals.
 */
private fun settledWith(registry: ChipRegistry): Module =
    (petshop.overriding(single<ChipRegistry> { registry }).onAFreshDatabase() + observed)
        .subgraph<Observed>()
        .overridingConfig("petshop.outboxEvery = 50ms")

private val settled: Module = settledWith(FakeRegistry())

/**
 * The whole path on the service's own backend and clock: shop, outbox, relay, bus, consumer. What the
 * relay does on each tick, refusals included, is RelaySpec's, on a clock the test moves.
 */
class EventsSpec {

    @Test
    fun `an adoption reaches a consumer through the outbox and the bus`() = story {
        testApp(settled) { app: Observed ->
            When("Ada adopts Nibbles") { app.shop.adopt(PetId(1), by = "Ada") }
            val tally = Then("the consumer counts a tortoise adopted, once it has caught up").eventually(5.seconds) {
                app.projection.tally().also { it.adopted(Species.Tortoise) shouldBe 1 }
            }
            And("it counted one event, and none twice") {
                tally.events shouldBe 1
                tally.duplicates shouldBe 0
            }
        }
    }

    @Test
    fun `an event delivered twice is counted once`() = story {
        val barnaby = Given("Bea's adoption of Barnaby, as an event") {
            PetAdopted(seq = 7, pet = Pet(PetId(2), "Barnaby", Species.Dog, adopted = true), by = "Bea")
        }
        testApp(settled) { app: Observed ->
            When("the bus delivers it twice") {
                app.bus.publish(barnaby)
                app.bus.publish(barnaby)
            }
            val tally = Then("the consumer sees the second as a duplicate").eventually(5.seconds) {
                app.projection.tally().also { it.duplicates shouldBe 1 }
            }
            And("counts the adoption once, because the bus is at-least-once and it knows an event by its seq") {
                tally.adopted(Species.Dog) shouldBe 1
                tally.events shouldBe 1
            }
        }
    }

    @Test
    fun `an adoption the registry refused reaches the consumer as a return`() = story {
        val down = Given("a registry that is down") { settledWith(FakeRegistry(refusing = Unreachable("down"))) }
        testApp(down) { app: Observed ->
            When("Ada adopts Nibbles") { app.shop.adopt(PetId(1), by = "Ada") }
            val tally = Then("the consumer hears of the adoption and of its undoing").eventually(5.seconds) {
                app.projection.tally().also { it.events shouldBe 2 }
            }
            And("the adoption was already recorded, so the undo is an event and the count goes back") {
                tally.adopted(Species.Tortoise) shouldBe 0
            }
        }
    }
}

private fun Tally.adopted(species: Species): Int = bySpecies.single { it.species == species }.adopted
