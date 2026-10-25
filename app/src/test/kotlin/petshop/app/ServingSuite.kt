package petshop.app

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeRight
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldNotContain
import io.github.matthewjones372.lark.junit.story
import io.github.matthewjones372.lark.metrics
import io.github.matthewjones372.lark.micrometer.MicrometerMetrics
import io.github.matthewjones372.lark.resource
import io.github.matthewjones372.pelican.jetty.PelicanServer
import io.github.matthewjones372.pelican.test.apiClient
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

object ServingSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("the whole shop starts on Jetty and answers") { _ ->
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

        test("a request is a line in the scrape") { _ ->
            // Counted where the shop counts outside a suite: lark-junit binds each test a capture of its own.
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

        test("the reference is served beside Swagger, in the shop's colours, over the same document") { _ ->
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
                        (reference.statusCode() shouldBe 200) and
                            (reference.body() shouldContain "<rapi-doc ") and
                            (reference.body() shouldContain "allow-try=\"true\"") and
                            (reference.body() shouldContain "primary-color=\"#C2562B\"") and
                            (reference.body() shouldContain "slot=\"nav-logo\"") and
                            (reference.body() shouldContain "fonts.googleapis.com")
                    } and And("Swagger is where it was, and the document every client reads carries no logo") {
                        (get("/api-docs").statusCode() shouldBe 200) and
                            (get("/openapi.json").body() shouldNotContain "x-logo")
                    }
                }.shouldBeRight { it }
            }
        }
    }
}
