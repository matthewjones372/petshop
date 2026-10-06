package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import petshop.domain.AlreadyAdopted
import petshop.domain.ChipRegistry
import petshop.domain.PetId
import petshop.domain.PetShop
import kotlin.time.Duration.Companion.seconds

/** What a test of a restart holds of each run of the shop: the shop, and the arrivals so they run. */
private class Running(val shop: PetShop)

private val running: Module = single { shop: PetShop, _: Arrivals -> Running(shop) }

/**
 * The shop stopped and started again on the same database, as a deploy or a crash does. The catalogue is a
 * table written in the same transaction as each event, so a restart opens the shop as it was left.
 */
class RestartSpec {

    private val database = TestPostgres.fresh()

    private val shop: Module =
        (petshop.overriding(single<ChipRegistry> { FakeRegistry() }).onDatabase(database) + running)
            .subgraph<Running>()
            .overridingConfig("petshop.arrivalsEvery = 50ms")

    @Test
    fun `a restarted shop remembers who adopted what, and numbers new arrivals on`() = story {
        val before = When("Ada adopts Nibbles, and the shop runs long enough for pets to arrive") {
            testApp(shop) { app: Running ->
                app.shop.adopt(PetId(1), by = "Ada").shouldBeRight()
                Thread.sleep(200)
                app.shop.all()
            }
        }
        testApp(shop) { app: Running ->
            Then("the restarted shop has Nibbles adopted") { app.shop.find(PetId(1))?.adopted shouldBe true }
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
}
