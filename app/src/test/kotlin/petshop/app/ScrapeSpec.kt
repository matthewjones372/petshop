package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.metrics
import io.github.matthewjones372.lark.micrometer.MicrometerMetrics
import io.kotest.matchers.string.shouldContain
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.junit.jupiter.api.Test
import petshop.domain.PetId
import petshop.domain.PetShop

/**
 * What a scrape of this service says after somebody adopts something.
 *
 * The registry is this test's own rather than the graph's global one, so two tests running together
 * cannot read each other's numbers.
 */
class ScrapeSpec {

    private val settled: Module = shopWith(FakeRegistry())

    @Test
    fun `an adoption is a line in the scrape, with the outcome as a label`() = story {
        val registry = Given("a Prometheus registry of this test's own") { PrometheusMeterRegistry(PrometheusConfig.DEFAULT) }
        val scrape = When("Ada adopts Nibbles, and Bea asks for her too") {
            metrics.locally(MicrometerMetrics(registry)) {
                testApp<PetShop, Unit>(settled) { shop ->
                    shop.adopt(PetId(1), "Ada")
                    shop.adopt(PetId(1), "Bea")
                }
                registry.scrape()
            }
        }
        Then("there is one series per outcome, which is what lets a single query answer either question") {
            scrape shouldContain """petshop_adoptions_total{outcome="taken""""
            scrape shouldContain """petshop_adoptions_total{outcome="already_adopted""""
        }
        And("timed recorded into a distribution, which Prometheus reads as a summary") {
            scrape shouldContain "petshop_adopt_duration_count"
        }
    }
}
