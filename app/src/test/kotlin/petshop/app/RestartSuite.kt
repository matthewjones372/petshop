package petshop.app

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.expect
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeLeft
import io.github.matthewjones372.lark.junit.shouldBeRight
import io.github.matthewjones372.lark.junit.shouldContainAll
import io.github.matthewjones372.lark.junit.story
import io.github.matthewjones372.lark.resource
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

object RestartSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("a restarted shop remembers who adopted what, and numbers new arrivals on") { _ ->
            val shop = shopOn(TestPostgres.fresh(), FakeRegistry()).overridingConfig("petshop.arrivalsEvery = 50ms")
            story {
                val (adopted, before) = When("Ada adopts Nibbles, and the shop runs long enough for pets to arrive") {
                    testApp(shop) { app: Running ->
                        expect(app.shop.adopt(PetId(1), by = "Ada").isRight()) to
                            waitUntil(5.seconds, { pets -> pets.size > opening.size }) { app.shop.all() }
                    }
                }
                val arrived = (before.size > opening.size) shouldBe true
                testApp(shop) { app: Running ->
                    adopted and arrived and Then("the restarted shop has Nibbles adopted") {
                        app.shop.find(PetId(1))?.standing shouldBe Adopted("Ada")
                    } and And("somebody asking for her is told she is taken") {
                        app.shop.adopt(PetId(1), by = "Bea") shouldBeLeft AlreadyAdopted(1)
                    } and And(
                        "new pets arrive with ids of their own, and every pet from before is still there as it was",
                    ) {
                        // A reused id would replace an earlier pet in the table, so it shows as one gone or changed.
                        val after = waitUntil(5.seconds, { it.size > before.size && it.containsAll(before) }) {
                            app.shop.all()
                        }
                        ((after.size > before.size) shouldBe true) and (after shouldContainAll before)
                    }
                }
            }
        }

        test("an adoption left unconfirmed when the shop stopped is settled when it starts again") { _ ->
            val database = TestPostgres.fresh()
            story {
                val down = ScriptedRegistry(
                    transfers = { ScriptedRegistry.Transfer.RecordsButLosesTheAnswer },
                    answers = { _, n -> n == 1 },
                )
                val unconfirmed = When(
                    "Ada adopts Nibbles, the registry records her but cannot be read back, and the shop stops",
                ) {
                    testApp(shopOn(database, down)) { app: Running ->
                        app.shop.adopt(PetId(1), by = "Ada").shouldBeRight { it.standing shouldBe Adopting("Ada") }
                    }
                }
                val back = ScriptedRegistry(keepers = mapOf(PetId(1) to "Ada"))
                testApp(shopOn(database, back)) { app: Running ->
                    unconfirmed and Then("the restarted shop asks the registry, which says Ada keeps her") {
                        waitUntil(5.seconds, { it == Adopted("Ada") }) { app.shop.find(PetId(1))?.standing } shouldBe
                            Adopted("Ada")
                    }
                }
            }
        }
    }
}
