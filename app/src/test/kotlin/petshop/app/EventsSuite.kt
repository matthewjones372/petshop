package petshop.app

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.story
import io.github.matthewjones372.lark.resource
import petshop.api.Tally
import petshop.app.ScriptedRegistry.Transfer.LosesTheRequest
import petshop.app.ScriptedRegistry.Transfer.Records
import petshop.domain.Adopted
import petshop.domain.ChipRegistry
import petshop.domain.OnShelf
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetId
import petshop.domain.PetReturned
import petshop.domain.PetShop
import petshop.domain.Species
import kotlin.time.Duration.Companion.seconds

private class Observed(val shop: PetShop, val bus: EventBus, val projection: Projection)

/** The relay is a dependency so that it runs: a background job nothing reaches is not started. */
private val observed: Module =
    single { shop: PetShop, bus: EventBus, projection: Projection, _: OutboxRelay ->
        Observed(shop, bus, projection)
    }

private fun settledWith(registry: ChipRegistry): Module =
    (Petshop.module.overriding(single<ChipRegistry> { registry }).onAFreshDatabase() + observed)
        .subgraph<Observed>()
        .overridingConfig("petshop.outboxEvery = 50ms")

private val settled: Module = settledWith(FakeRegistry())

object EventsSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("an adoption reaches a consumer through the outbox and the bus") { _ ->
            story {
                testApp(settled) { app: Observed ->
                    When("Ada adopts Nibbles") { app.shop.adopt(PetId(1), by = "Ada") }
                    val (counted, tally) =
                        Then("the consumer counts a tortoise adopted, once it has caught up").eventually(5.seconds) {
                            app.projection.tally().let { (it.adopted(Species.Tortoise) shouldBeOrRetry 1) to it }
                        }
                    counted and And("it counted one event, and none twice") {
                        (tally.events shouldBe 1) and (tally.duplicates shouldBe 0)
                    }
                }
            }
        }

        test("an event delivered twice is counted once") { _ ->
            story {
                val barnaby = Given("Bea's adoption of Barnaby, as an event") {
                    PetAdopted(
                        seq = 7,
                        pet = Pet(PetId(2), "Barnaby", Species.Dog, standing = Adopted("Bea")),
                        by = "Bea",
                    )
                }
                testApp(settled) { app: Observed ->
                    When("the bus delivers it twice") {
                        app.bus.publish(barnaby)
                        app.bus.publish(barnaby)
                    }
                    val (duplicate, tally) =
                        Then("the consumer sees the second as a duplicate").eventually(5.seconds) {
                            app.projection.tally().let { (it.duplicates shouldBeOrRetry 1) to it }
                        }
                    val once = "counts the adoption once, because the bus is at-least-once " +
                        "and it knows an event by its seq"
                    duplicate and And(once) { (tally.adopted(Species.Dog) shouldBe 1) and (tally.events shouldBe 1) }
                }
            }
        }

        test("a return from before ADR 0001 still takes its adoption off the count") { _ ->
            story {
                val barnaby = Given("Barnaby, adopted by Bea and then returned, as the topic's history holds him") {
                    Pet(PetId(2), "Barnaby", Species.Dog, standing = Adopted("Bea"))
                }
                testApp(settled) { app: Observed ->
                    When("the bus delivers the adoption and then the return") {
                        app.bus.publish(PetAdopted(seq = 7, pet = barnaby, by = "Bea"))
                        app.bus.publish(PetReturned(seq = 8, pet = barnaby.copy(standing = OnShelf)))
                    }
                    val (both, tally) = Then("the consumer counts both events").eventually(5.seconds) {
                        app.projection.tally().let { (it.events shouldBeOrRetry 2) to it }
                    }
                    both and And("no dog is counted adopted") { tally.adopted(Species.Dog) shouldBe 0 }
                }
            }
        }

        test("an adoption the registry refused never reaches the consumer") { _ ->
            story {
                val losing = Given("a registry that loses the first transfer and records the rest") {
                    settledWith(ScriptedRegistry(transfers = { if (it == 1) LosesTheRequest else Records }))
                }
                testApp(losing) { app: Observed ->
                    When("Ada's adoption of Nibbles is refused, and then Bea adopts Barnaby") {
                        app.shop.adopt(PetId(1), by = "Ada")
                        app.shop.adopt(PetId(2), by = "Bea")
                    }
                    val (one, tally) = Then("the consumer hears one event, Bea's").eventually(5.seconds) {
                        app.projection.tally().let { (it.events shouldBeOrRetry 1) to it }
                    }
                    one and And("no tortoise was ever counted adopted: the bus never heard Ada's adoption begin") {
                        (tally.adopted(Species.Tortoise) shouldBe 0) and (tally.adopted(Species.Dog) shouldBe 1)
                    }
                }
            }
        }
    }
}

private fun Tally.adopted(species: Species): Int = bySpecies.single { it.species == species }.adopted
