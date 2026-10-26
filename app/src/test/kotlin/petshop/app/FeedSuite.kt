package petshop.app

import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.overriding
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.app.singleOf
import io.github.matthewjones372.surefold.app.subgraph
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.story
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.StreamBackend
import io.github.matthewjones372.surefold.stream.map
import io.github.matthewjones372.surefold.stream.run
import io.github.matthewjones372.surefold.stream.runCollect
import io.github.matthewjones372.surefold.stream.take
import io.github.matthewjones372.surefold.test.eventually
import petshop.domain.Adopted
import petshop.domain.ChipRegistry
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.Species
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private class Watched(val bus: EventBus, val feed: Feed, val projection: Projection, val streams: StreamBackend)

private val watched: Module =
    (Petshop.module.overriding(single<ChipRegistry> { FakeRegistry() }).onAFreshDatabase() + singleOf(::Watched))
        .subgraph<Watched>()

object FeedSuite : Suite() {
    init {
        test("a watcher sees each event once, however often the bus delivers it") {
            story {
                val barnaby = Given("Bea's adoption of Barnaby, and then a parrot arriving") {
                    PetAdopted(
                        seq = 7,
                        pet = Pet(PetId(2), "Barnaby", Species.Dog, standing = Adopted("Bea")),
                        by = "Bea",
                    )
                }
                val pickle = PetArrived(seq = 8, pet = Pet(PetId(9), "Pickle", Species.Parrot))
                testApp(watched) { app: Watched ->
                    val seen = When("someone is watching") {
                        app.feed.watch().map { it.seq }.take(2).runCollect().run(app.streams)
                    }
                    Then("the feed has them as a watcher").eventually(5.seconds) { app.feed.watchers shouldBe 1 }
                    When("the bus delivers the adoption twice, then the arrival") {
                        app.bus.publish(barnaby)
                        app.bus.publish(barnaby)
                        app.bus.publish(pickle)
                    }
                    Then("the watcher sees the adoption and the arrival, in order, and the repeat not at all") {
                        seen.toCompletableFuture().get(5, TimeUnit.SECONDS) shouldBe Exit.Done(listOf(7L, 8L))
                    }
                }
            }
        }

        test("a watcher is told the trace each event was published in") {
            story {
                val pickle =
                    Given("a parrot arriving") { PetArrived(seq = 8, pet = Pet(PetId(9), "Pickle", Species.Parrot)) }
                testApp(watched) { app: Watched ->
                    val seen = When("someone is watching") {
                        app.feed.watch().map { traceOf(it.traceparent) ?: "none" }.take(1).runCollect().run(app.streams)
                    }
                    Then("the feed has them as a watcher").eventually(5.seconds) { app.feed.watchers shouldBe 1 }
                    When("the arrival is published inside a trace") { inATrace { app.bus.publish(pickle) } }
                    Then("the watcher is given that trace, so a page can continue it") {
                        seen.toCompletableFuture().get(5, TimeUnit.SECONDS) shouldBe Exit.Done(listOf(A_TRACE))
                    }
                }
            }
        }

        test("with nobody watching, the feed holds nothing back for the first watcher") {
            story {
                testApp(watched) { app: Watched ->
                    When("an adoption is delivered before anyone watches") {
                        val nibbles = Pet(PetId(1), "Nibbles", Species.Tortoise, standing = Adopted("Ada"))
                        app.bus.publish(PetAdopted(seq = 3, pet = nibbles, by = "Ada"))
                    }
                    Then("the projection has counted it").eventually(5.seconds) {
                        app.projection.tally().events shouldBe 1
                    }
                    val seen = When("someone starts watching, and then a parrot arrives") {
                        val first = app.feed.watch().map { it.seq }.take(1).runCollect().run(app.streams)
                        eventually(5.seconds) { app.feed.watchers shouldBe 1 }
                        app.bus.publish(PetArrived(seq = 4, pet = Pet(PetId(9), "Pickle", Species.Parrot)))
                        first
                    }
                    Then("the first thing they see is the arrival, not the earlier adoption") {
                        seen.toCompletableFuture().get(5, TimeUnit.SECONDS) shouldBe Exit.Done(listOf(4L))
                    }
                }
            }
        }
    }
}
