package petshop.app

import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.assert.shouldBeRight
import io.github.matthewjones372.lark.test.story
import io.github.matthewjones372.pelican.jetty.PelicanServer
import io.github.matthewjones372.pelican.test.apiClient
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import petshop.api.ShopCodecs
import petshop.api.listPets
import petshop.api.toDto
import petshop.domain.ChipRegistry
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

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

    @Test
    fun `a request is a line in the scrape`() = story {
        val served = Given("the service on any free port") {
            Petshop.module.overriding(single<ChipRegistry> { FakeRegistry() })
                .onAFreshDatabase()
                .overridingConfig("petshop.port = 0")
        }
        val scrape = When("somebody asks what is on the shelf, and Prometheus scrapes") {
            served.use { server: PelicanServer ->
                apiClient(server.baseUrl, ShopCodecs).use { shop -> shop.call(listPets, Unit) }
                HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(server.baseUrl + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body()
            }
        }.shouldBeRight()
        Then("the scrape counts that request") { scrape shouldContain "petshop_requests_total" }
    }

    @Test
    fun `the reference is served beside Swagger, in the shop's colours, over the same document`() = story {
        val served = Given("the service on any free port") {
            Petshop.module.overriding(single<ChipRegistry> { FakeRegistry() })
                .onAFreshDatabase()
                .overridingConfig("petshop.port = 0")
        }
        served.use { server: PelicanServer ->
            val http = HttpClient.newHttpClient()
            fun get(path: String) = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl + path)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            val reference = When("someone opens /reference") { get("/reference") }
            Then("it is RapiDoc, which can send a request, in the shop's colours and fonts, with the paw for a logo") {
                reference.statusCode() shouldBe 200
                reference.body() shouldContain "<rapi-doc "
                reference.body() shouldContain "allow-try=\"true\""
                reference.body() shouldContain "primary-color=\"#C2562B\""
                reference.body() shouldContain "slot=\"nav-logo\""
                reference.body() shouldContain "fonts.googleapis.com"
            }
            And("Swagger is where it was, and the document every client reads carries no logo") {
                get("/api-docs").statusCode() shouldBe 200
                get("/openapi.json").body() shouldNotContain "x-logo"
            }
        }.shouldBeRight()
    }
}
