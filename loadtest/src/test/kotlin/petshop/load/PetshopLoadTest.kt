package petshop.load

import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.http.exec
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.junit5.LoadTest
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.report.writeHtmlReport
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import petshop.app.petshop
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole application under load, started by its own graph in this process: the actor, the arrivals
 * stream and the endpoints, exactly as `main` starts them. Nothing is stubbed, and the graph is given
 * back when the block returns.
 */
class PetshopLoadTest {

    private val browse = step("browse the shop")

    private val adoptTaken = step("adopt a pet somebody already has")

    private fun adoptOnce(baseUrl: String) {
        java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(java.net.URI.create("$baseUrl/pets/1/adoption"))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                .build(),
            java.net.http.HttpResponse.BodyHandlers.discarding(),
        )
    }

    @LoadTest
    fun `browsing holds up at two hundred a second`(proofload: Proofload) {
        val outcome = petshop.use { server: PelicanServer ->
            val api = http.baseUrl(server.baseUrl)
            val shopping = scenario("browsing") { exec(browse, api.get("/pets").expecting(200)) }

            proofload.run(shopping.at(200.perSecond, over = 10.seconds))
        }

        val result = outcome.getOrNull() ?: error("the petshop did not start")

        result.writeHtmlReport(Path.of("build/reports/proofload/browsing.html"))
        result.failed shouldBe 0L
        result[browse].responseTime.p99 shouldBeLessThan 250.milliseconds
    }

    /**
     * The claim the actor exists for, checked under load rather than in a unit test: a pet is sold
     * once. The step expects a 409, so a single 200 — two people told they got the same tortoise —
     * fails the run, and so does a 500.
     */
    @LoadTest
    fun `a pet already adopted is never sold again`(proofload: Proofload) {
        val outcome = petshop.use { server: PelicanServer ->
            adoptOnce(server.baseUrl)

            val api = http.baseUrl(server.baseUrl)
            val rush = scenario("the rush") {
                exec(adoptTaken, api.post("/pets/1/adoption").expecting(409))
            }

            proofload.run(rush.at(200.perSecond, over = 10.seconds))
        }

        val result = outcome.getOrNull() ?: error("the petshop did not start")

        result.writeHtmlReport(Path.of("build/reports/proofload/the-rush.html"))
        withClue("every one of them was told the tortoise was gone") { result.failed shouldBe 0L }
        result[adoptTaken].count shouldBe 2000L
    }
}
