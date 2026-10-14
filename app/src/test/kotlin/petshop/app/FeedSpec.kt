package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.runCollect
import io.github.matthewjones372.lark.stream.take
import io.github.matthewjones372.lark.test.eventually
import io.github.matthewjones372.lark.test.story
import io.kotest.matchers.shouldBe
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import petshop.domain.ChipRegistry
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.Species

private class Watched(val bus: EventBus, val feed: Feed, val projection: Projection, val streams: StreamBackend)

private val watched: Module =
    (Petshop.module.overriding(single<ChipRegistry> { FakeRegistry() }).onAFreshDatabase() + singleOf(::Watched))
        .subgraph<Watched>()

class FeedSpec {

    @Test
    fun `a watcher sees each event once, however often the bus delivers it`() = story {
        val barnaby = Given("Bea's adoption of Barnaby, and then a parrot arriving") {
            PetAdopted(seq = 7, pet = Pet(PetId(2), "Barnaby", Species.Dog, adopted = true), by = "Bea")
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

    @Test
    fun `with nobody watching, the feed holds nothing back for the first watcher`() = story {
        testApp(watched) { app: Watched ->
            When("an adoption is delivered before anyone watches") {
                val nibbles = Pet(PetId(1), "Nibbles", Species.Tortoise, adopted = true)
                app.bus.publish(PetAdopted(seq = 3, pet = nibbles, by = "Ada"))
            }
            Then("the projection has counted it").eventually(5.seconds) { app.projection.tally().events shouldBe 1 }
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
