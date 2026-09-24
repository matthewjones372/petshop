package petshop.app

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.notFound
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.serverError
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.parMap
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import petshop.domain.NotChipped
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.RegistryDown
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * The shop against a registry that is a real HTTP server on a random port, with the shop's own client
 * talking to it. Nothing of the shop's is faked: the actor, the client, its JSON and its timeout are
 * the ones `main` starts. The only change to the graph is one line of configuration saying where the
 * registry is.
 *
 * This is the test a fake [petshop.domain.ChipRegistry] cannot be. A fake skips the path, the body,
 * the status codes and the timeout — every decision [HttpChipRegistry] makes — and those are what
 * break when somebody else's service changes or has a bad day.
 */
class RegistrySpec {

    @JvmField
    @RegisterExtension
    val registry: WireMockExtension = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build()

    /** The shop as `main` starts it, with the registry somewhere else. The file keeps everything else. */
    private fun shop(timeout: String = "2s"): Module =
        petshop.subgraph<PetShop>().overridingConfig(
            """
            petshop.registry.baseUrl = "${registry.baseUrl()}"
            petshop.registry.timeout = $timeout
            """.trimIndent(),
        )

    private fun chipOnRecord(pet: Long, number: String = "98100000000000$pet") {
        registry.stubFor(get("/chips/$pet").willReturn(okJson("""{"number":"$number","keeper":"Petshop"}""")))
    }

    private fun keeperRecorded(number: String, keeper: String) {
        registry.stubFor(
            post("/chips/$number/keeper")
                .withRequestBody(equalToJson("""{"keeper":"$keeper"}"""))
                .willReturn(okJson("""{"number":"$number","keeper":"$keeper"}""")),
        )
    }

    @Test
    fun `an adoption looks the chip up and records the new keeper`() {
        chipOnRecord(pet = 1, number = "981000000000001")
        keeperRecorded(number = "981000000000001", keeper = "Ada")

        val adopted = testApp(shop()) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") }

        adopted.getOrNull()?.adopted shouldBe true
        withClue("the request is what the registry documents: JSON, to the chip the lookup answered with") {
            registry.verify(
                postRequestedFor(urlPathMatching("/chips/981000000000001/keeper"))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withRequestBody(equalToJson("""{"keeper":"Ada"}""")),
            )
        }
    }

    @Test
    fun `a pet with no chip on record stays in the shop`() {
        registry.stubFor(get("/chips/1").willReturn(notFound()))

        val (answer, after) = testApp(shop()) { shop: PetShop ->
            shop.adopt(PetId(1), by = "Ada") to shop.find(PetId(1))
        }

        answer.leftOrNull() shouldBe NotChipped(1)
        after?.adopted shouldBe false
        withClue("there is no chip to transfer, so nothing is sent") {
            registry.verify(0, postRequestedFor(urlPathMatching("/chips/.*")))
        }
    }

    @Test
    fun `a registry that takes too long is an outage, not a hang`() {
        registry.stubFor(get("/chips/1").willReturn(okJson("""{"number":"1","keeper":"Petshop"}""").withFixedDelay(5_000)))

        val (answer, took) = testApp(shop(timeout = "300ms")) { shop: PetShop ->
            measureTimedValue { shop.adopt(PetId(1), by = "Ada") }
        }

        answer.leftOrNull() shouldBe RegistryDown(1)
        withClue("the shop's timeout decides how long an adopter waits, not the registry") {
            took shouldBeLessThan 2.seconds
        }
    }

    @Test
    fun `a connection reset while recording the keeper puts the pet back on the shelf`() {
        chipOnRecord(pet = 1, number = "981000000000001")
        registry.stubFor(post("/chips/981000000000001/keeper").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

        val (answer, after) = testApp(shop()) { shop: PetShop ->
            val refused = shop.adopt(PetId(1), by = "Ada")
            // The registry comes back, and the pet is still there to be adopted.
            keeperRecorded(number = "981000000000001", keeper = "Bea")
            refused to shop.adopt(PetId(1), by = "Bea")
        }

        answer.leftOrNull() shouldBe RegistryDown(1)
        after.getOrNull()?.adopted shouldBe true
    }

    @Test
    fun `a registry answering 500 is an outage too`() {
        registry.stubFor(get("/chips/1").willReturn(serverError().withBody("the database is on fire")))

        testApp(shop()) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") }.leftOrNull() shouldBe RegistryDown(1)
    }

    @Test
    fun `twenty adopters race for one tortoise and the registry hears from one of them`() {
        chipOnRecord(pet = 1, number = "981000000000001")
        registry.stubFor(
            post("/chips/981000000000001/keeper")
                .willReturn(okJson("""{"number":"981000000000001","keeper":"the winner"}""")),
        )

        val outcomes = testApp(shop()) { shop: PetShop ->
            parMap((1..20).toList()) { who -> shop.adopt(PetId(1), by = "adopter $who") }
        }

        outcomes.count { it.isRight() } shouldBe 1
        withClue("the actor settles the race before anybody calls out, so the losers cost the registry nothing") {
            registry.verify(1, getRequestedFor(urlPathMatching("/chips/1")))
            registry.verify(1, postRequestedFor(urlPathMatching("/chips/.*/keeper")))
        }
    }
}
