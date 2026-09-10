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
}
