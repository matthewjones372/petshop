package petshop.app

import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.story
import petshop.domain.ChipRegistry

/** Hears every message and answers none, as a wedged actor does. */
private object Silent : ActorRef<Shop> {
    override val address = Address("test", "/silent", 1)

    override fun tell(message: Shop) = Unit
}

object ReadinessSuite : Suite() {
    init {
        test("a shop whose actor does not answer is not ready, though its shelf still shows pets") {
            story {
                val silent: Module = Given(
                    "an actor that answers nothing, and a window still showing the opening shelf",
                ) {
                    Petshop.module.overriding(
                        single<ChipRegistry> { FakeRegistry() } +
                            single<ActorRef<Shop>> { Silent } +
                            single<ShopWindow> { ShopWindow().apply { show(opening.associateBy { it.id }) } },
                    ).onAFreshDatabase().subgraph<Readiness>()
                }
                val failing =
                    When("readiness is asked") { testApp(silent) { readiness: Readiness -> readiness.failing() } }
                Then("the shop check fails") { failing shouldContain "shop" }
            }
        }
    }
}
