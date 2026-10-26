package petshop.app

import io.github.matthewjones372.surefold.actor.ActorRef
import io.github.matthewjones372.surefold.actor.Reply
import io.github.matthewjones372.surefold.actor.ask
import io.github.matthewjones372.surefold.app.subgraph
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.assert.shouldBeLeft
import io.github.matthewjones372.surefold.assert.shouldBeRight
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.test.story
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import petshop.domain.Adopted
import petshop.domain.OnShelf
import petshop.domain.Pet
import petshop.domain.PetId
import kotlin.time.Duration.Companion.seconds

/** Only Adoption sends Confirm and Return, so these speak to the actor directly, as a settling retry would. */
class ShopActorSpec {

    private val shop = Petshop.module.onAFreshDatabase().subgraph<ActorRef<Shop>>()

    @Test
    fun `a confirm retried after its write landed is answered with the adopted pet`() = story {
        testApp(shop) { ref: ActorRef<Shop> ->
            Given("Ada's adoption of Nibbles, confirmed") {
                ref.ask(within = 3.seconds) { reply -> Adopt(PetId(1), "Ada", reply) }.shouldBeRight().shouldBeRight()
                ref.settle { reply -> Confirm(PetId(1), "Ada", reply) }.shouldBeRight()
            }
            val again = When("the confirm comes again") { ref.settle { reply -> Confirm(PetId(1), "Ada", reply) } }
            Then("Nibbles is still Ada's") { again.shouldBeRight().standing shouldBe Adopted("Ada") }
        }
    }

    @Test
    fun `a confirm for a pet nobody is adopting is a bug, and changes nothing`() = story {
        testApp(shop) { ref: ActorRef<Shop> ->
            val onShelf = When("Nibbles, on the shelf, is confirmed for Ada") {
                ref.settle { reply -> Confirm(PetId(1), "Ada", reply) }
            }
            Then("it is refused as invalid") { onShelf.shouldBeLeft() shouldBe Unsettled.Invalid(OnShelf) }
            val missing = And("a pet the shop never had is confirmed") {
                ref.settle { reply -> Confirm(PetId(99), "Ada", reply) }
            }
            And("that is refused as invalid too") { missing.shouldBeLeft() shouldBe Unsettled.Invalid(null) }
        }
    }

    @Test
    fun `a return for a pet already adopted is a bug, and changes nothing`() = story {
        testApp(shop) { ref: ActorRef<Shop> ->
            Given("Ada's adoption of Nibbles, confirmed") {
                ref.ask(within = 3.seconds) { reply -> Adopt(PetId(1), "Ada", reply) }.shouldBeRight().shouldBeRight()
                ref.settle { reply -> Confirm(PetId(1), "Ada", reply) }.shouldBeRight()
            }
            val back = When("her adoption is returned") { ref.settle { reply -> Return(PetId(1), "Ada", reply) } }
            Then("it is refused as invalid") { back.shouldBeLeft() shouldBe Unsettled.Invalid(Adopted("Ada")) }
            val missing = And("a pet the shop never had is returned") {
                ref.settle { reply -> Return(PetId(99), "Ada", reply) }
            }
            And("that is refused as invalid too") { missing.shouldBeLeft() shouldBe Unsettled.Invalid(null) }
        }
    }

    @Test
    fun `a return retried after the pet is back on the shelf is answered with the pet`() = story {
        testApp(shop) { ref: ActorRef<Shop> ->
            val back = When("Nibbles, already on the shelf, is returned") {
                ref.settle { reply -> Return(PetId(1), "Ada", reply) }
            }
            Then("the return's work is done") { back.shouldBeRight().standing shouldBe OnShelf }
        }
    }
}

private fun ActorRef<Shop>.settle(message: (Reply<Either<Unsettled, Pet>>) -> Shop): Either<Unsettled, Pet> =
    ask(within = 3.seconds, message).shouldBeRight()
