package petshop.app

import io.github.matthewjones372.pelican.jetty.PelicanServer
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.overriding
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.app.typesafe.overridingConfig
import io.github.matthewjones372.surefold.app.use
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeRight
import io.github.matthewjones372.surefold.junit.shouldContain
import io.github.matthewjones372.surefold.junit.shouldNotContain
import io.github.matthewjones372.surefold.junit.story
import io.github.matthewjones372.surefold.metrics
import io.github.matthewjones372.surefold.micrometer.MicrometerMetrics
import petshop.api.ShopCodecs
import petshop.api.listPets
import petshop.api.toDto
import petshop.domain.ChipRegistry
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private fun theShop(): Module = Petshop.module.overriding(single<ChipRegistry> { FakeRegistry() })
    .onAFreshDatabase()
    .overridingConfig("petshop.port = 0")

object ServingSuite : Suite() {
    init {
        test("the whole shop starts on Jetty and answers") {
            story {
                val served = Given("the service, as application.conf has it, on any free port") { theShop() }
                val pets = When("somebody asks what is on the shelf") {
                    served.use { server: PelicanServer ->
                        apiClient(server.baseUrl, ShopCodecs).use { shop -> shop.call(listPets, Unit) }
                    }
                }
                pets.shouldBeRight { shelf ->
                    Then("the shop's opening pets are on it") { shelf.take(opening.size) shouldBe opening.toDto() }
                }
            }
        }

        test("a request is a line in the scrape") {
            // Counted where the shop counts outside a suite: surefold-junit binds each test a capture of its own.
            metrics.locally(MicrometerMetrics()) {
                story {
                    val served = Given("the service on any free port") { theShop() }
                    val scrape = When("somebody asks what is on the shelf, and Prometheus scrapes") {
                        served.use { server: PelicanServer ->
                            apiClient(server.baseUrl, ShopCodecs).use { shop -> shop.call(listPets, Unit) }
                            HttpClient.newHttpClient().send(
                                HttpRequest.newBuilder(URI.create(server.baseUrl + "/metrics")).build(),
                                HttpResponse.BodyHandlers.ofString(),
                            ).body()
                        }
                    }
                    scrape.shouldBeRight { body ->
                        Then("the scrape counts that request") { body shouldContain "petshop_requests_total" }
                    }
                }
            }
        }

        test("the reference is served beside Swagger, in the shop's colours, over the same document") {
            story {
                val served = Given("the service on any free port") { theShop() }
                served.use { server: PelicanServer ->
                    val http = HttpClient.newHttpClient()
                    fun get(path: String) = http.send(
                        HttpRequest.newBuilder(URI.create(server.baseUrl + path)).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )

                    val reference = When("someone opens /reference") { get("/reference") }
                    Then(
                        "it is RapiDoc, which can send a request, " +
                            "in the shop's colours and fonts, with the paw for a logo",
                    ) {
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
                } shouldBeRight Unit
            }
        }
    }
}
