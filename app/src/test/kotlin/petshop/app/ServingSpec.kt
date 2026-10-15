package petshop.app

import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.test.story
import io.github.matthewjones372.pelican.jetty.PelicanServer
import io.github.matthewjones372.pelican.test.apiClient
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import petshop.api.ShopCodecs
import petshop.api.listPets
import petshop.api.toDto
import petshop.domain.ChipRegistry

class ServingSpec {

    @Test
    fun `the whole shop starts on Jetty and answers`() = story {
        val served = Given("the service, as application.conf has it, on any free port") {
            Petshop.module.overriding(single<ChipRegistry> { FakeRegistry() })
                .onAFreshDatabase()
                .overridingConfig("petshop.port = 0")
        }
        val pets = When("somebody asks what is on the shelf") {
            served.use { server: PelicanServer ->
                apiClient(server.baseUrl, ShopCodecs).use { shop -> shop.call(listPets, Unit) }
            }.shouldBeRight()
        }
        Then("the shop's opening pets are on it") { pets.take(opening.size) shouldBe opening.toDto() }
    }
}
