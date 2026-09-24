package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.render
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.parMap
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import petshop.domain.AlreadyAdopted
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.RegistryDown
import petshop.domain.Unreachable
import kotlin.time.Duration.Companion.seconds

/**
 * The shop under test, and nothing else: no port bound, no documents served, and no arrivals turning
 * up mid-assertion. `subgraph` is what makes that a line rather than a second wiring.
 */
class AdoptionSpec {

    // The shop needs a system, an actor, a tracer and a registry. Not a port, not a config file, and —
    // since the actor stopped pretending to depend on them — not the settings either. The registry is
    // a fake at the node: this file is about who gets the pet, and RegistrySpec is about the wire.
    private val settled: Module = shopWith(FakeRegistry())

    @Test
    fun `twenty people adopt one tortoise and one of them gets it`() {
        val outcomes = testApp(settled) { shop: PetShop ->
            parMap((1..20).toList()) { who -> shop.adopt(PetId(1), by = "adopter $who") }
        }

        withClue("an actor handles one message at a time, so the race has exactly one winner") {
            outcomes.count { it.isRight() } shouldBe 1
        }
        outcomes.count { it.leftOrNull() == AlreadyAdopted(1) } shouldBe 19
    }

    @Test
    fun `a keeper the registry will not record puts the pet back on the shelf`() {
        val refused = shopWith(FakeRegistry(refusing = Unreachable("down for maintenance")))

        val (first, after) = testApp(refused) { shop: PetShop ->
            shop.adopt(PetId(1), by = "Ada") to shop.find(PetId(1))
        }

        first.leftOrNull() shouldBe RegistryDown(1)
        withClue("the actor said yes before the registry said no, and the no has to undo it") {
            after?.adopted shouldBe false
        }
    }

    @Test
    fun `the subgraph binds no port and serves no documents`() {
        val drawn = settled.render()

        withClue("what a test of the shop needs is the shop") {
            drawn.contains("PelicanServer") shouldBe false
            drawn.contains("Arrivals") shouldBe false
            drawn.contains("RegistrySettings") shouldBe false
        }
    }
}

/** That the file is read at all, which a graph makes easy to assert and easy to forget. */
class SettingsSpec {

    @Test
    fun `a test changes one setting and the file keeps the rest`() {
        val faster = petshop.subgraph<Settings>().overridingConfig("petshop.arrivalsEvery = 1s")

        val read = testApp(faster) { settings: Settings -> settings }

        read.arrivalsEvery shouldBe 1.seconds
        withClue("the port was never restated, and came from application.conf") {
            read.port shouldBe 8080
        }
    }

    @Test
    fun `the settings come from the file rather than a default`() {
        val read = testApp(petshop.subgraph<Settings>()) { settings: Settings -> settings }

        read.port shouldBe 8080
        read.arrivalsEvery shouldBe 5.seconds
    }

    @Test
    fun `every node that takes the settings uses them`() {
        val drawn = petshop.render()

        withClue("a dependency nothing reads is an edge that lies, and the diagram repeats it") {
            drawn.contains("Settings --> ActorRef_Shop_") shouldBe false
        }
        drawn shouldContain "Settings --> Arrivals"
    }
}
