package petshop.app

import com.github.tomakehurst.wiremock.http.Fault
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.test.wiremock.PelicanWireMock
import io.github.matthewjones372.surefold.Limit
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.overriding
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.app.testNode
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.forAll
import io.github.matthewjones372.surefold.junit.holds
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeGreaterThanOrEqual
import io.github.matthewjones372.surefold.junit.shouldBeLeft
import io.github.matthewjones372.surefold.junit.shouldBeLessThan
import io.github.matthewjones372.surefold.junit.shouldBeRight
import io.github.matthewjones372.surefold.junit.story
import io.github.matthewjones372.surefold.parMap
import petshop.domain.Adopted
import petshop.domain.Chip
import petshop.domain.ChipRegistry
import petshop.domain.NotChipped
import petshop.domain.OnShelf
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.RegistryDown
import petshop.registry.ChipRecord
import petshop.registry.NewKeeper
import petshop.registry.Problem
import petshop.registry.lookupChip
import petshop.registry.noSuchChip
import petshop.registry.recordKeeper
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

private val chip = ChipRecord("981000000000001", keeper = "Petshop")

/** [block] against a chip registry of the test's own, and a shop that calls it. */
private fun <A> onARegistry(block: (registry: PelicanWireMock, shop: Module) -> A): A =
    PelicanWireMock(KotlinxCodecs).use { registry -> block(registry, shopCalling(registry)) }

object RegistrySuite : Suite() {
    init {
        test("an adoption looks the chip up and records the new keeper") {
            onARegistry { registry, shop ->
                story {
                    Given("a registry with Nibbles' chip, that records Ada as her keeper") {
                        registry.stub(lookupChip, 1L) answers ok(chip)
                        registry.stub(
                            recordKeeper,
                            In2(chip.number, NewKeeper("Ada")),
                        ) answers ok(chip.copy(keeper = "Ada"))
                    }
                    val adopted = When("Ada adopts Nibbles") {
                        testApp(shop) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") }
                    }
                    val hers = Then("Nibbles is hers") { adopted.shouldBeRight { it.standing shouldBe Adopted("Ada") } }
                    val recorded = And("the keeper was recorded against the chip the lookup answered with") {
                        holds { registry.verify(recordKeeper, In2(chip.number, NewKeeper("Ada"))) }
                    }
                    hers and recorded
                }
            }
        }

        test("a pet with no chip on record stays in the shop") {
            onARegistry { registry, shop ->
                story {
                    Given(
                        "a registry with no chip for Nibbles",
                    ) { registry.stub(lookupChip, 1L) answers noSuchChip(Problem("never chipped")) }
                    val (answer, after) = When("Ada adopts Nibbles") {
                        testApp(shop) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") to shop.find(PetId(1)) }
                    }
                    Then("she is told Nibbles has no chip") { answer shouldBeLeft NotChipped(1) } and
                        And("Nibbles stays in the shop") { after?.standing shouldBe OnShelf } and
                        And("with no chip to transfer, nothing was sent") { registry.calls(recordKeeper) shouldBe 0 }
                }
            }
        }

        test("a registry that takes too long is an outage, not a hang") {
            onARegistry { registry, _ ->
                story {
                    Given("a registry that takes five seconds to answer, and a shop that waits 300 ms") {
                        registry.stub(lookupChip, 1L).answers(ok(chip), after = 5.seconds)
                    }
                    val (answer, took) = When("Ada adopts Nibbles") {
                        testApp(shopCalling(registry, timeout = 300.milliseconds)) { shop: PetShop ->
                            measureTimedValue { shop.adopt(PetId(1), by = "Ada") }
                        }
                    }
                    Then("she is told the registry is down") { answer shouldBeLeft RegistryDown(1) } and
                        And(
                            "she was not kept waiting: the shop's timeout decides that, not the registry",
                        ) { took shouldBeLessThan 2.seconds }
                }
            }
        }

        test("a connection reset while recording the keeper puts the pet back on the shelf") {
            onARegistry { registry, shop ->
                story {
                    Given("a registry that resets the connection recording Ada, and records Bea") {
                        registry.stub(lookupChip, 1L) answers ok(chip)
                        registry.stub(
                            recordKeeper,
                            In2(chip.number, NewKeeper("Ada")),
                        ) fails Fault.CONNECTION_RESET_BY_PEER
                        registry.stub(
                            recordKeeper,
                            In2(chip.number, NewKeeper("Bea")),
                        ) answers ok(chip.copy(keeper = "Bea"))
                    }
                    val (refused, retried) = When("Ada adopts Nibbles, and then Bea does") {
                        testApp(shop) { shop: PetShop ->
                            shop.adopt(PetId(1), by = "Ada") to shop.adopt(PetId(1), by = "Bea")
                        }
                    }
                    Then("Ada is told the registry is down") { refused shouldBeLeft RegistryDown(1) } and
                        And(
                            "her adoption was undone, so Nibbles was still there for Bea",
                        ) { retried.shouldBeRight { it.standing shouldBe Adopted("Bea") } }
                }
            }
        }

        test("a status the contract never declared is an outage too") {
            onARegistry { registry, shop ->
                story {
                    Given(
                        "a registry that answers 500, which its contract never declared",
                    ) { registry.stub(lookupChip, 1L) breaksWith 500 }
                    val answer = When("Ada adopts Nibbles") {
                        testApp(shop) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") }
                    }
                    Then("she is told the registry is down") { answer shouldBeLeft RegistryDown(1) }
                }
            }
        }

        test("a registry that keeps failing stops being asked") {
            onARegistry { registry, shop ->
                story {
                    Given(
                        "a registry that answers 500 to every lookup",
                    ) { registry.stub(lookupChip, 1L) breaksWith 500 }
                    val answers = When("Ada asks for Nibbles six times, each refusal putting her back on the shelf") {
                        testApp(shop) { shop: PetShop -> (1..6).map { shop.adopt(PetId(1), by = "Ada") } }
                    }
                    Then("every time she is told the registry is down") {
                        answers.forAll { it shouldBeLeft RegistryDown(1) }
                    } and And(
                        "the registry was asked five times: after five failures in a row the breaker answers for it",
                    ) {
                        registry.calls(lookupChip) shouldBe 5
                    }
                }
            }
        }

        test("twenty adopters race for one tortoise and the registry hears from one of them") {
            onARegistry { registry, shop ->
                story {
                    Given("a registry that knows Nibbles' chip and records any keeper") {
                        registry.stub(lookupChip, 1L) answers ok(chip)
                        registry.stub(recordKeeper) { (number, keeper) -> ok(ChipRecord(number, keeper.keeper)) }
                    }
                    val outcomes = When("twenty people ask for Nibbles at once") {
                        testApp(
                            shop,
                        ) { shop: PetShop ->
                            val adopters = (1..20).toList()
                            parMap(adopters) { who -> shop.adopt(PetId(1), by = "adopter $who") }
                        }
                    }
                    Then("one of them gets her") { outcomes.count { it.isRight() } shouldBe 1 } and
                        And(
                            "the registry heard from that one only: " +
                                "the actor settles the race before anybody calls out",
                        ) {
                            (registry.calls(lookupChip) shouldBe 1) and (registry.calls(recordKeeper) shouldBe 1)
                        }
                }
            }
        }

        test("calls past the registry's limit wait their turn and are answered") {
            onARegistry { registry, _ ->
                story {
                    Given("a registry that takes 500 ms over each of four chips, and a limit of two calls at once") {
                        registry.stub(lookupChip, 0L) answers ok(chip)
                        (1L..4L).forEach { id ->
                            registry.stub(lookupChip, id).answers(ok(chip), after = 500.milliseconds)
                        }
                    }
                    val (answers, took) = When("four lookups start together, after one that warms the client up") {
                        val limited = Petshop.module.overriding(
                            single<RegistrySettings> { RegistrySettings(registry.baseUrl, 5.seconds) } +
                                single<Limit> { Limit.fixed(2, "chip-registry") },
                        )
                        testNode(limited) { chips: ChipRegistry ->
                            chips.lookup(PetId(0))
                            measureTimedValue { parMap((1L..4L).toList()) { id -> chips.lookup(PetId(id)) } }
                        }
                    }
                    Then("every one is answered, none refused") {
                        answers.forAll { it shouldBeRight Chip(chip.number, chip.keeper) }
                    } and And("they went two at a time, so it took two rounds of the registry's 500 ms") {
                        took shouldBeGreaterThanOrEqual 1.seconds
                    }
                }
            }
        }
    }
}
