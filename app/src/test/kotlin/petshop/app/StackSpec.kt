package petshop.app

import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.testNode
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.test.story
import io.github.matthewjones372.pelican.test.apiClient
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import petshop.api.KotlinxShopCodecs
import petshop.api.listPets
import petshop.api.toDto
import petshop.domain.ChipRegistry

class StackSpec {

    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource("jetty, kotlinx", "jetty, jackson", "pekko, kotlinx", "pekko, jackson")
    fun `the whole shop starts on each stack, with each codec, and answers`(stack: String, codec: String) = story {
        val served = Given("the service with petshop.stack = $stack and petshop.codec = $codec") {
            Petshop.module.overriding(single<ChipRegistry> { FakeRegistry() })
                .onAFreshDatabase()
                .overridingConfig("petshop { stack = $stack, codec = $codec, port = 0 }")
        }
        val (started, pets) = When("somebody asks what is on the shelf") {
            served.use { server: Shopfront ->
                apiClient(server.baseUrl, KotlinxShopCodecs).use { shop ->
                    "${server.stack}, ${server.codec}".lowercase() to shop.call(listPets, Unit)
                }
            }.shouldBeRight()
        }
        Then("it was started as the settings said") { started shouldBe "$stack, $codec" }
        And("the shop's opening pets are on it") { pets.take(opening.size) shouldBe opening.toDto() }
    }

    @Test
    fun `left as application conf has it, the shop runs on Jetty with kotlinx`() = story {
        val graph = Given("the service assembled from application.conf as it ships") { Petshop.module }
        Then("its engine is Jetty's") { testNode(graph) { engine: Engine -> engine.shouldBeInstanceOf<OnJetty>() } }
        And("its codec is kotlinx") { testNode(graph) { codec: Codec -> codec shouldBe Codec.Kotlinx } }
    }

    @Test
    fun `a stack that is neither refuses the start, and says what it was given`() = story {
        val graph = Given("stack = netty") { Petshop.module.overridingConfig("petshop.stack = netty") }
        val refused = When("the engine is started") {
            shouldThrow<IllegalStateException> { testNode(graph) { _: Engine -> Unit } }
        }
        Then("the start is refused, naming the value and the two it could have been") {
            refused.message shouldContain "'netty' is not pekko or jetty"
        }
    }
}
