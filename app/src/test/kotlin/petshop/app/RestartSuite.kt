package petshop.app

import dev.surefold.app.Module
import dev.surefold.app.overriding
import dev.surefold.app.single
import dev.surefold.app.subgraph
import dev.surefold.app.testApp
import dev.surefold.app.typesafe.overridingConfig
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.junit.shouldBeLeft
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.shouldContainAll
import dev.surefold.junit.story
import dev.surefold.test.eventually
import petshop.domain.Adopted
import petshop.domain.Adopting
import petshop.domain.AlreadyAdopted
import petshop.domain.ChipRegistry
import petshop.domain.PetId
import petshop.domain.PetShop
import kotlin.time.Duration.Companion.seconds

private class Running(val shop: PetShop)

private val running: Module = single { shop: PetShop, _: Arrivals -> Running(shop) }

private fun shopOn(database: DatabaseSettings, registry: ChipRegistry): Module =
    (Petshop.module.overriding(single<ChipRegistry> { registry }).onDatabase(database) + running)
        .subgraph<Running>()

object RestartSuite : Suite() {
    init {
        test("a restarted shop remembers who adopted what, and numbers new arrivals on") {
            val shop = shopOn(TestPostgres.fresh(), FakeRegistry()).overridingConfig("petshop.arrivalsEvery = 50ms")
            story {
                val before = When("Ada adopts Nibbles, and the shop runs long enough for pets to arrive") {
                    testApp(shop) { app: Running ->
                        expect(app.shop.adopt(PetId(1), by = "Ada").isRight())
                        eventually(5.seconds) { app.shop.all().also { it.size shouldBeGreaterThan opening.size } }
                    }
                }
                (before.size > opening.size) shouldBe true
                testApp(shop) { app: Running ->
                    Then("the restarted shop has Nibbles adopted") {
                        app.shop.find(PetId(1))?.standing shouldBe Adopted("Ada")
                    }
                    And("somebody asking for her is told she is taken") {
                        app.shop.adopt(PetId(1), by = "Bea") shouldBeLeft AlreadyAdopted(1)
                    }
                    // A reused id would replace an earlier pet in the table, so it shows as one gone or changed.
                    And(
                        "new pets arrive with ids of their own, and every pet from before is still there as it was",
                    ).eventually(5.seconds) {
                        val after = app.shop.all()
                        (after.size > before.size) shouldBe true
                        after shouldContainAll before
                    }
                }
            }
        }

        test("an adoption left unconfirmed when the shop stopped is settled when it starts again") {
            val database = TestPostgres.fresh()
            story {
                val down = ScriptedRegistry(
                    transfers = { ScriptedRegistry.Transfer.RecordsButLosesTheAnswer },
                    answers = { _, n -> n == 1 },
                )
                When(
                    "Ada adopts Nibbles, the registry records her but cannot be read back, and the shop stops",
                ) {
                    testApp(shopOn(database, down)) { app: Running ->
                        app.shop.adopt(PetId(1), by = "Ada").shouldBeRight { it.standing shouldBe Adopting("Ada") }
                    }
                }
                val back = ScriptedRegistry(keepers = mapOf(PetId(1) to "Ada"))
                testApp(shopOn(database, back)) { app: Running ->
                    Then("the restarted shop asks the registry, which says Ada keeps her").eventually(5.seconds) {
                        app.shop.find(PetId(1))?.standing shouldBe Adopted("Ada")
                    }
                }
            }
        }
    }
}
