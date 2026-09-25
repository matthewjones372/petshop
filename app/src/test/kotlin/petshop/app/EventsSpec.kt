package petshop.app

import arrow.core.Either
import arrow.core.left
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.pekko.actor.ActorSystem
import org.junit.jupiter.api.Test
import petshop.api.Tally
import petshop.domain.ChipRegistry
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.ShopEvent
import petshop.domain.Species
import petshop.domain.Unreachable
import java.util.concurrent.atomic.AtomicInteger

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

/** The shop, the relay, the bus and the consumer, with [registry] at the node. No port, no arrivals. */
private fun settledWith(registry: ChipRegistry): Module =
    (petshop.overriding(single<ChipRegistry> { registry }) + observed)
        .subgraph<Observed>()
        .overridingConfig("petshop.outboxEvery = 50ms")

private val settled: Module = settledWith(FakeRegistry())

/** A bus that turns away its first [refusals] events, the way a full broker does. */
private class Refusing(private val bus: EventBus, refusals: Int) : EventBus by bus {

    private val left = AtomicInteger(refusals)

    override fun publish(event: ShopEvent): Either<BusRefused, ShopEvent> =
        if (left.getAndDecrement() > 0) BusRefused(event.seq, "full").left() else bus.publish(event)
}

class EventsSpec {

    @Test
    fun `an adoption reaches a consumer through the outbox and the bus`() {
        val tally = testApp(settled) { app: Observed ->
            app.shop.adopt(PetId(1), by = "Ada")
            app.projection.settlesOn { tally -> tally.adopted(Species.Tortoise) == 1 }
        }

        tally.events shouldBe 1
        tally.duplicates shouldBe 0
    }

    @Test
    fun `an event the bus refused is still in the outbox, and goes again`() {
        val refusing = settled.overriding(
            single { system: ActorSystem -> Refusing(HubBus(system), refusals = 3) }.boundTo<EventBus>(),
        )

        val tally = testApp(refusing) { app: Observed ->
            app.shop.adopt(PetId(1), by = "Ada")
            app.projection.settlesOn { tally -> tally.adopted(Species.Tortoise) == 1 }
        }

        withClue("three refusals on one event cost three ticks, not the event") {
            tally.events shouldBe 1
        }
    }

    @Test
    fun `an event delivered twice is counted once`() {
        val barnaby = PetAdopted(seq = 7, pet = Pet(PetId(2), "Barnaby", Species.Dog, adopted = true), by = "Bea")

        val tally = testApp(settled) { app: Observed ->
            app.bus.publish(barnaby)
            app.bus.publish(barnaby)
            app.projection.settlesOn { tally -> tally.duplicates == 1 }
        }

        withClue("the bus is at-least-once, so the consumer knows an event by its seq") {
            tally.adopted(Species.Dog) shouldBe 1
            tally.events shouldBe 1
        }
    }

    @Test
    fun `an adoption the registry refused reaches the consumer as a return`() {
        val tally = testApp(settledWith(FakeRegistry(refusing = Unreachable("down")))) { app: Observed ->
            app.shop.adopt(PetId(1), by = "Ada")
            app.projection.settlesOn { tally -> tally.events == 2 }
        }

        withClue("the adoption was already recorded, so the undo is an event and the count goes back") {
            tally.adopted(Species.Tortoise) shouldBe 0
        }
    }
}

private fun Tally.adopted(species: Species): Int = bySpecies.single { it.species == species }.adopted

/** The consumer is behind the shop by a tick and a hop, so a test waits for it rather than sleeping. */
private fun Projection.settlesOn(done: (Tally) -> Boolean): Tally {
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!done(tally())) {
        check(System.nanoTime() < deadline) { "the projection never got there: ${tally()}" }
        Thread.sleep(20)
    }
    return tally()
}
