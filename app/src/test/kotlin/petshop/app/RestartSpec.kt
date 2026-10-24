package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.assert.shouldBeLeft
import io.github.matthewjones372.lark.assert.shouldBeRight
import io.github.matthewjones372.lark.test.eventually
import io.github.matthewjones372.lark.test.story
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import petshop.domain.Adopted
import petshop.domain.Adopting
import petshop.domain.AlreadyAdopted
import petshop.domain.ChipRegistry
import petshop.domain.PetId
import petshop.domain.PetShop
import kotlin.time.Duration.Companion.seconds

private class Running(val shop: PetShop)

private val running: Module = single { shop: PetShop, _: Arrivals -> Running(shop) }

class RestartSpec {

    private val database = TestPostgres.fresh()

    private val shop: Module =
        (Petshop.module.overriding(single<ChipRegistry> { FakeRegistry() }).onDatabase(database) + running)
            .subgraph<Running>()
            .overridingConfig("petshop.arrivalsEvery = 50ms")

    @Test
    fun `a restarted shop remembers who adopted what, and numbers new arrivals on`() = story {
        val before = When("Ada adopts Nibbles, and the shop runs long enough for pets to arrive") {
            testApp(shop) { app: Running ->
                app.shop.adopt(PetId(1), by = "Ada").shouldBeRight()
                eventually(5.seconds) { app.shop.all().also { pets -> (pets.size > opening.size) shouldBe true } }
            }
        }
        testApp(shop) { app: Running ->
            Then("the restarted shop has Nibbles adopted") { app.shop.find(PetId(1))?.standing shouldBe Adopted("Ada") }
            And("somebody asking for her is told she is taken") {
                app.shop.adopt(PetId(1), by = "Bea") shouldBeLeft AlreadyAdopted(1)
            }
            And("new pets arrive with ids of their own, and every pet from before is still there as it was")
                .eventually(5.seconds) {
                    // A reused id would replace an earlier pet in the table, so it shows as one gone or changed.
                    val after = app.shop.all()
                    (after.size > before.size) shouldBe true
                    after shouldContainAll before
                }
        }
    }

    @Test
    fun `an adoption left unconfirmed when the shop stopped is settled when it starts again`() = story {
        val down = ScriptedRegistry(
            transfers = { ScriptedRegistry.Transfer.RecordsButLosesTheAnswer },
            answers = { _, n -> n == 1 },
        )
        When("Ada adopts Nibbles, the registry records her but cannot be read back, and the shop stops") {
            testApp(shopCalling(down)) { app: Running ->
                app.shop.adopt(PetId(1), by = "Ada").shouldBeRight().standing shouldBe Adopting("Ada")
            }
        }
        val back = ScriptedRegistry(keepers = mapOf(PetId(1) to "Ada"))
        testApp(shopCalling(back)) { app: Running ->
            Then("the restarted shop asks the registry, which says Ada keeps her").eventually(5.seconds) {
                app.shop.find(PetId(1))?.standing shouldBe Adopted("Ada")
            }
        }
    }

    private fun shopCalling(registry: ChipRegistry): Module =
        (Petshop.module.overriding(single<ChipRegistry> { registry }).onDatabase(database) + running)
            .subgraph<Running>()
}
