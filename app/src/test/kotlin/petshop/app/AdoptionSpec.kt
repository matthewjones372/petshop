package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.render
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.parMap
import io.github.matthewjones372.lark.test.story
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.sql.SQLTransientConnectionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import petshop.domain.AlreadyAdopted
import petshop.domain.ChipRegistry
import petshop.domain.NotRecorded
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.RegistryDown
import petshop.domain.ShopEvent
import petshop.domain.Unreachable

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
    fun `twenty people adopt one tortoise and one of them gets it`() = story {
        val outcomes = When("twenty people ask for Nibbles at once") {
            testApp(settled) { shop: PetShop ->
                parMap((1..20).toList()) { who -> shop.adopt(PetId(1), by = "adopter $who") }
            }
        }
        Then("exactly one gets her, because an actor handles one message at a time") {
            outcomes.count { it.isRight() } shouldBe 1
        }
        And("the other nineteen are told she is taken") {
            outcomes.count { it.leftOrNull() == AlreadyAdopted(1) } shouldBe 19
        }
    }

    @Test
    fun `a keeper the registry will not record puts the pet back on the shelf`() = story {
        val refused = Given("a registry that will not record a new keeper") {
            shopWith(FakeRegistry(refusing = Unreachable("down for maintenance")))
        }
        val (first, after) = When("Ada adopts Nibbles") {
            testApp(refused) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") to shop.find(PetId(1)) }
        }
        Then("she is told the registry is down") { first shouldBeLeft RegistryDown(1) }
        And("Nibbles is back on the shelf: the actor said yes before the registry said no, and the no undid it") {
            after?.adopted shouldBe false
        }
    }

    @Test
    fun `an adoption the outbox could not record is refused, and the pet stays on the shelf`() = story {
        val asked = AtomicInteger()
        val counting = object : ChipRegistry by FakeRegistry() {
            override fun lookup(id: PetId) = FakeRegistry().lookup(id).also { asked.incrementAndGet() }
        }
        val down = object : Outbox {
            override fun record(pet: Pet, numbered: (seq: Long) -> ShopEvent): ShopEvent =
                throw SQLTransientConnectionException("the pool had nothing to lend")

            override fun shelf(opening: List<Pet>): List<Pet> = opening

            override fun claim(limit: Int, publish: (List<ShopEvent>) -> List<ShopEvent>) = emptyList<ShopEvent>()
        }
        val unrecorded = Given("an outbox that cannot write, and a registry that counts what it is asked") {
            petshop.overriding(single<ChipRegistry> { counting })
                .overriding(single<Outbox> { down })
                .subgraph<PetShop>()
        }
        val (answer, after) = When("Ada adopts Nibbles") {
            testApp(unrecorded) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") to shop.find(PetId(1)) }
        }
        Then("she is told it was not recorded") { answer shouldBeLeft NotRecorded(1) }
        And("a sale nobody could write down did not happen: Nibbles is on the shelf, and the registry was not asked") {
            after?.adopted shouldBe false
            asked.get() shouldBe 0
        }
    }

    @Test
    fun `the subgraph binds no port and serves no documents`() = story {
        val drawn = When("the shop's subgraph is drawn") { settled.render() }
        Then("it holds no server, no arrivals and no registry settings: what a test of the shop needs is the shop") {
            drawn.contains("PelicanServer") shouldBe false
            drawn.contains("Arrivals") shouldBe false
            drawn.contains("RegistrySettings") shouldBe false
        }
    }
}

/** That the file is read at all, which a graph makes easy to assert and easy to forget. */
class SettingsSpec {

    @Test
    fun `a test changes one setting and the file keeps the rest`() = story {
        val faster = Given("the settings with arrivals every second") {
            petshop.subgraph<Settings>().overridingConfig("petshop.arrivalsEvery = 1s")
        }
        val read = When("they are read") { testApp(faster) { settings: Settings -> settings } }
        Then("arrivals are every second") { read.arrivalsEvery shouldBe 1.seconds }
        And("the port, never restated, came from application.conf") { read.port shouldBe 8080 }
    }

    @Test
    fun `the settings come from the file rather than a default`() = story {
        val read = When("the settings are read with nothing overridden") {
            testApp(petshop.subgraph<Settings>()) { settings: Settings -> settings }
        }
        Then("they are application.conf's") {
            read.host shouldBe "127.0.0.1"
            read.port shouldBe 8080
            read.arrivalsEvery shouldBe 5.seconds
        }
    }

    @Test
    fun `every node that takes the settings uses them`() = story {
        val drawn = When("the whole graph is drawn") { petshop.render() }
        Then("the shop's actor does not take the settings: a dependency nothing reads is an edge that lies") {
            drawn.contains("Settings --> ActorRef_Shop_") shouldBe false
        }
        And("the arrivals do") { drawn shouldContain "Settings --> Arrivals" }
    }
}
