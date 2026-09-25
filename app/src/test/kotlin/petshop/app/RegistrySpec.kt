package petshop.app

import com.github.tomakehurst.wiremock.http.Fault
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.parMap
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.test.wiremock.PelicanWireMockExtension
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import petshop.domain.NotChipped
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.RegistryDown
import petshop.registry.ChipRecord
import petshop.registry.NewKeeper
import petshop.registry.Problem
import petshop.registry.lookupChip
import petshop.registry.noSuchChip
import petshop.registry.recordKeeper

/**
 * The shop against a registry that is a real HTTP server, with the shop's own client talking to it.
 * Nothing of the shop's is faked: the actor, the generated client, its JSON and its timeout are the
 * ones `main` starts.
 *
 * The stubs are written in the registry's endpoints, not its URLs: `stub(lookupChip, 1L)` is the
 * request the client sends for pet 1, and what it answers is a value the endpoint declares. Change
 * the registry's contract and these stubs move with it, or stop compiling.
 */
class RegistrySpec {

    @JvmField
    @RegisterExtension
    val registry = PelicanWireMockExtension(JacksonCodecs)

    private val shop = shopCalling(registry)

    private val chip = ChipRecord("981000000000001", keeper = "Petshop")

    @Test
    fun `an adoption looks the chip up and records the new keeper`() {
        registry.stub(lookupChip, 1L) answers ok(chip)
        registry.stub(recordKeeper, In2(chip.number, NewKeeper("Ada"))) answers ok(chip.copy(keeper = "Ada"))

        val adopted = testApp(shop) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") }

        adopted.shouldBeRight().adopted shouldBe true
        withClue("the keeper is recorded against the chip the lookup answered with") {
            registry.verify(recordKeeper, In2(chip.number, NewKeeper("Ada")))
        }
    }

    @Test
    fun `a pet with no chip on record stays in the shop`() {
        registry.stub(lookupChip, 1L) answers noSuchChip(Problem("never chipped"))

        val (answer, after) = testApp(shop) { shop: PetShop ->
            shop.adopt(PetId(1), by = "Ada") to shop.find(PetId(1))
        }

        answer shouldBeLeft NotChipped(1)
        after?.adopted shouldBe false
        withClue("there is no chip to transfer, so nothing is sent") { registry.calls(recordKeeper) shouldBe 0 }
    }

    @Test
    fun `a registry that takes too long is an outage, not a hang`() {
        registry.stub(lookupChip, 1L).answers(ok(chip), after = 5.seconds)

        val (answer, took) = testApp(shopCalling(registry, timeout = 300.milliseconds)) { shop: PetShop ->
            measureTimedValue { shop.adopt(PetId(1), by = "Ada") }
        }

        answer shouldBeLeft RegistryDown(1)
        withClue("the shop's timeout decides how long an adopter waits, not the registry") {
            took shouldBeLessThan 2.seconds
        }
    }

    @Test
    fun `a connection reset while recording the keeper puts the pet back on the shelf`() {
        registry.stub(lookupChip, 1L) answers ok(chip)
        registry.stub(recordKeeper, In2(chip.number, NewKeeper("Ada"))) fails Fault.CONNECTION_RESET_BY_PEER
        registry.stub(recordKeeper, In2(chip.number, NewKeeper("Bea"))) answers ok(chip.copy(keeper = "Bea"))

        val (refused, retried) = testApp(shop) { shop: PetShop ->
            shop.adopt(PetId(1), by = "Ada") to shop.adopt(PetId(1), by = "Bea")
        }

        refused shouldBeLeft RegistryDown(1)
        withClue("Ada's adoption was undone, so the pet was still there for Bea") {
            retried.shouldBeRight().adopted shouldBe true
        }
    }

    @Test
    fun `a status the contract never declared is an outage too`() {
        registry.stub(lookupChip, 1L) breaksWith 500

        testApp(shop) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") } shouldBeLeft RegistryDown(1)
    }

    @Test
    fun `twenty adopters race for one tortoise and the registry hears from one of them`() {
        registry.stub(lookupChip, 1L) answers ok(chip)
        registry.stub(recordKeeper) { (number, keeper) -> ok(ChipRecord(number, keeper.keeper)) }

        val outcomes = testApp(shop) { shop: PetShop ->
            parMap((1..20).toList()) { who -> shop.adopt(PetId(1), by = "adopter $who") }
        }

        outcomes.count { it.isRight() } shouldBe 1
        withClue("the actor settles the race before anybody calls out, so the losers cost the registry nothing") {
            registry.calls(lookupChip) shouldBe 1
            registry.calls(recordKeeper) shouldBe 1
        }
    }
}
