package petshop.app

import com.github.tomakehurst.wiremock.http.Fault
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.parMap
import io.github.matthewjones372.lark.test.story
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.test.wiremock.PelicanWireMockExtension
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import petshop.domain.Adopted
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

class RegistrySpec {

    @JvmField
    @RegisterExtension
    val registry = PelicanWireMockExtension(KotlinxCodecs)

    private val shop = shopCalling(registry)

    private val chip = ChipRecord("981000000000001", keeper = "Petshop")

    @Test
    fun `an adoption looks the chip up and records the new keeper`() = story {
        Given("a registry with Nibbles' chip, that records Ada as her keeper") {
            registry.stub(lookupChip, 1L) answers ok(chip)
            registry.stub(recordKeeper, In2(chip.number, NewKeeper("Ada"))) answers ok(chip.copy(keeper = "Ada"))
        }
        val adopted = When("Ada adopts Nibbles") { testApp(shop) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") } }
        Then("Nibbles is hers") { adopted.shouldBeRight().standing shouldBe Adopted("Ada") }
        And("the keeper was recorded against the chip the lookup answered with") {
            registry.verify(recordKeeper, In2(chip.number, NewKeeper("Ada")))
        }
    }

    @Test
    fun `a pet with no chip on record stays in the shop`() = story {
        Given(
            "a registry with no chip for Nibbles",
        ) { registry.stub(lookupChip, 1L) answers noSuchChip(Problem("never chipped")) }
        val (answer, after) = When("Ada adopts Nibbles") {
            testApp(shop) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") to shop.find(PetId(1)) }
        }
        Then("she is told Nibbles has no chip") { answer shouldBeLeft NotChipped(1) }
        And("Nibbles stays in the shop") { after?.standing shouldBe OnShelf }
        And("with no chip to transfer, nothing was sent") { registry.calls(recordKeeper) shouldBe 0 }
    }

    @Test
    fun `a registry that takes too long is an outage, not a hang`() = story {
        Given("a registry that takes five seconds to answer, and a shop that waits 300 ms") {
            registry.stub(lookupChip, 1L).answers(ok(chip), after = 5.seconds)
        }
        val (answer, took) = When("Ada adopts Nibbles") {
            testApp(shopCalling(registry, timeout = 300.milliseconds)) { shop: PetShop ->
                measureTimedValue { shop.adopt(PetId(1), by = "Ada") }
            }
        }
        Then("she is told the registry is down") { answer shouldBeLeft RegistryDown(1) }
        And(
            "she was not kept waiting: the shop's timeout decides that, not the registry",
        ) { took shouldBeLessThan 2.seconds }
    }

    @Test
    fun `a connection reset while recording the keeper puts the pet back on the shelf`() = story {
        Given("a registry that resets the connection recording Ada, and records Bea") {
            registry.stub(lookupChip, 1L) answers ok(chip)
            registry.stub(recordKeeper, In2(chip.number, NewKeeper("Ada"))) fails Fault.CONNECTION_RESET_BY_PEER
            registry.stub(recordKeeper, In2(chip.number, NewKeeper("Bea"))) answers ok(chip.copy(keeper = "Bea"))
        }
        val (refused, retried) = When("Ada adopts Nibbles, and then Bea does") {
            testApp(shop) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") to shop.adopt(PetId(1), by = "Bea") }
        }
        Then("Ada is told the registry is down") { refused shouldBeLeft RegistryDown(1) }
        And(
            "her adoption was undone, so Nibbles was still there for Bea",
        ) { retried.shouldBeRight().standing shouldBe Adopted("Bea") }
    }

    @Test
    fun `a status the contract never declared is an outage too`() = story {
        Given(
            "a registry that answers 500, which its contract never declared",
        ) { registry.stub(lookupChip, 1L) breaksWith 500 }
        val answer = When("Ada adopts Nibbles") { testApp(shop) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") } }
        Then("she is told the registry is down") { answer shouldBeLeft RegistryDown(1) }
    }

    @Test
    fun `a registry that keeps failing stops being asked`() = story {
        Given("a registry that answers 500 to every lookup") { registry.stub(lookupChip, 1L) breaksWith 500 }
        val answers = When("Ada asks for Nibbles six times, each refusal putting her back on the shelf") {
            testApp(shop) { shop: PetShop -> (1..6).map { shop.adopt(PetId(1), by = "Ada") } }
        }
        Then("every time she is told the registry is down") { answers.forEach { it shouldBeLeft RegistryDown(1) } }
        And("the registry was asked five times: after five failures in a row the breaker answers for it") {
            registry.calls(lookupChip) shouldBe 5
        }
    }

    @Test
    fun `twenty adopters race for one tortoise and the registry hears from one of them`() = story {
        Given("a registry that knows Nibbles' chip and records any keeper") {
            registry.stub(lookupChip, 1L) answers ok(chip)
            registry.stub(recordKeeper) { (number, keeper) -> ok(ChipRecord(number, keeper.keeper)) }
        }
        val outcomes = When("twenty people ask for Nibbles at once") {
            testApp(
                shop,
            ) { shop: PetShop -> parMap((1..20).toList()) { who -> shop.adopt(PetId(1), by = "adopter $who") } }
        }
        Then("one of them gets her") { outcomes.count { it.isRight() } shouldBe 1 }
        And("the registry heard from that one only: the actor settles the race before anybody calls out") {
            registry.calls(lookupChip) shouldBe 1
            registry.calls(recordKeeper) shouldBe 1
        }
    }
}
