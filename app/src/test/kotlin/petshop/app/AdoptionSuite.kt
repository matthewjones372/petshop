package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.render
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeLeft
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.story
import io.github.matthewjones372.lark.parMap
import io.github.matthewjones372.lark.timeout
import petshop.domain.Adopted
import petshop.domain.AlreadyAdopted
import petshop.domain.BeingAdopted
import petshop.domain.ChipRegistry
import petshop.domain.NotRecorded
import petshop.domain.OnShelf
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.RegistryDown
import petshop.domain.ShopEvent
import petshop.domain.Unreachable
import java.sql.SQLTransientConnectionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

object AdoptionSuite : Suite() {
    private val settled: Module = shopWith(FakeRegistry())

    init {
        test("twenty people adopt one tortoise and one of them gets it") {
            story {
                val outcomes = When("twenty people ask for Nibbles at once") {
                    testApp(settled) { shop: PetShop ->
                        parMap((1..20).toList()) { who -> shop.adopt(PetId(1), by = "adopter $who") }
                    }
                }
                Then("exactly one gets her, because an actor handles one message at a time") {
                    outcomes.count { it.isRight() } shouldBe 1
                } and And(
                    "the other nineteen are told she is taken, or being adopted while her transfer is in flight",
                ) {
                    val told = outcomes.count {
                        it.leftOrNull() == AlreadyAdopted(1) || it.leftOrNull() == BeingAdopted(1)
                    }
                    told shouldBe 19
                }
            }
        }

        test("a keeper the registry will not record puts the pet back on the shelf") {
            story {
                val refused = Given("a registry that will not record a new keeper") {
                    shopWith(FakeRegistry(refusing = Unreachable("down for maintenance")))
                }
                val (first, after) = When("Ada adopts Nibbles") {
                    testApp(refused) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") to shop.find(PetId(1)) }
                }
                Then("she is told the registry is down") { first shouldBeLeft RegistryDown(1) } and
                    And(
                        "Nibbles is back on the shelf: the actor said yes before the registry said no, " +
                            "and the no undid it",
                    ) {
                        after?.standing shouldBe OnShelf
                    }
            }
        }

        test("an adoption the outbox could not record is refused, and the pet stays on the shelf") {
            story {
                val asked = AtomicInteger()
                val counting = object : ChipRegistry by FakeRegistry() {
                    override fun lookup(id: PetId) = FakeRegistry().lookup(id).also { asked.incrementAndGet() }
                }
                val down = object : Outbox {
                    override fun record(pet: Pet, numbered: (seq: Long) -> ShopEvent): ShopEvent =
                        throw SQLTransientConnectionException("the pool had nothing to lend")

                    override fun keep(pet: Pet): Unit =
                        throw SQLTransientConnectionException("the pool had nothing to lend")

                    override fun shelf(opening: List<Pet>): List<Pet> = opening

                    override fun claim(limit: Int, publish: (List<Claimed>) -> List<ShopEvent>) = emptyList<ShopEvent>()
                }
                val unrecorded = Given("an outbox that cannot write, and a registry that counts what it is asked") {
                    Petshop.module.overriding(single<ChipRegistry> { counting })
                        .overriding(single<Outbox> { down })
                        .subgraph<PetShop>()
                }
                val (answer, after) = When("Ada adopts Nibbles") {
                    testApp(unrecorded) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") to shop.find(PetId(1)) }
                }
                Then("she is told it was not recorded") { answer shouldBeLeft NotRecorded(1) } and
                    And(
                        "a sale nobody could write down did not happen: Nibbles is on the shelf, " +
                            "and the registry was not asked",
                    ) {
                        (after?.standing shouldBe OnShelf) and (asked.get() shouldBe 0)
                    }
            }
        }

        test("a read during a write sees the shelf as it was, and does not wait for the write") {
            story {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                // An adoption's first write is the one that takes the pet off the shelf, so that is the one held.
                val blocking = object : Outbox {
                    override fun record(pet: Pet, numbered: (seq: Long) -> ShopEvent): ShopEvent = numbered(1)

                    override fun keep(pet: Pet) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "the write was never released" }
                    }

                    override fun shelf(opening: List<Pet>): List<Pet> = opening

                    override fun claim(limit: Int, publish: (List<Claimed>) -> List<ShopEvent>): List<ShopEvent> =
                        emptyList()
                }
                val held = Given("an outbox that holds a write until the test lets it finish") {
                    Petshop.module.overriding(single<ChipRegistry> { FakeRegistry() })
                        .overriding(single<Outbox> { blocking })
                        .subgraph<PetShop>()
                }
                val (during, after) = When("Ada's adoption is held inside the write, and someone reads the shelf") {
                    testApp(held) { shop: PetShop ->
                        val adopting = Thread { shop.adopt(PetId(1), by = "Ada") }
                        adopting.start()
                        check(entered.await(5, TimeUnit.SECONDS)) { "the write never started" }
                        // Only the read is held to a second: the graph's start and the held write are not.
                        val seen = try {
                            timeout(1.seconds) { shop.all() to shop.find(PetId(1)) }
                        } finally {
                            release.countDown()
                        }
                        adopting.join(5_000)
                        seen to shop.find(PetId(1))
                    }
                }
                Then("the read saw Nibbles still for sale, without waiting out the ask") {
                    val (all, found) = during
                    (all.map { it.id.value } shouldBe opening.map { it.id.value }) and
                        (all.single { it.id == PetId(1) }.standing shouldBe OnShelf) and
                        (found?.standing shouldBe OnShelf)
                } and And("once the write has finished she has Nibbles") { after?.standing shouldBe Adopted("Ada") }
            }
        }

        test("the subgraph binds no port and serves no documents") {
            story {
                val drawn = When("the shop's subgraph is drawn") { settled.render() }
                Then(
                    "it holds no server, no arrivals and no registry settings: " +
                        "what a test of the shop needs is the shop",
                ) {
                    (drawn.contains("PelicanServer") shouldBe false) and
                        (drawn.contains("Arrivals") shouldBe false) and
                        (drawn.contains("RegistrySettings") shouldBe false)
                }
            }
        }
    }
}

object SettingsSuite : Suite() {
    init {
        test("a test changes one setting and the file keeps the rest") {
            story {
                val faster = Given("the settings with arrivals every second") {
                    Petshop.module.subgraph<Settings>().overridingConfig("petshop.arrivalsEvery = 1s")
                }
                val read = When("they are read") { testApp(faster) { settings: Settings -> settings } }
                Then("arrivals are every second") { read.arrivalsEvery shouldBe 1.seconds } and
                    And("the port, never restated, came from application.conf") { read.port shouldBe 8080 }
            }
        }

        test("the settings come from the file rather than a default") {
            story {
                val read = When("the settings are read with nothing overridden") {
                    testApp(Petshop.module.subgraph<Settings>()) { settings: Settings -> settings }
                }
                Then("they are application.conf's") {
                    (read.host shouldBe "127.0.0.1") and (read.port shouldBe 8080) and
                        (read.arrivalsEvery shouldBe 5.seconds)
                }
            }
        }

        test("every node that takes the settings uses them") {
            story {
                val drawn = When("the whole graph is drawn") { Petshop.module.render() }
                Then("the shop's actor does not take the settings: a dependency nothing reads is an edge that lies") {
                    drawn.contains("Settings --> ActorRef_Shop_") shouldBe false
                } and And("the arrivals do") { drawn shouldContain "Settings --> Arrivals" }
            }
        }
    }
}
