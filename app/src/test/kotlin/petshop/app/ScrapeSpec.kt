package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.metrics
import io.github.matthewjones372.lark.micrometer.MicrometerMetrics
import io.github.matthewjones372.lark.test.story
import io.kotest.matchers.string.shouldContain
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.junit.jupiter.api.Test
import petshop.domain.ChipRegistry
import petshop.domain.PetId
import petshop.domain.PetShop
import javax.sql.DataSource

/**
 * What a scrape of this service says after somebody adopts something.
 *
 * The registry is this test's own rather than the graph's global one, so two tests running together
 * cannot read each other's numbers.
 */
/** The graph's registry and its meters, with the shop and the pool started so their probes have something to ask. */
private class Scraped(val registry: PrometheusMeterRegistry, val meters: Meters)

private val scraped: Module =
    single { registry: PrometheusMeterRegistry, meters: Meters, _: PetShop, _: DataSource -> Scraped(registry, meters) }

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

    @Test
    fun `the scrape says how the JVM, the pool and every health check are`() = story {
        val service = Given("the shop on a fresh database") {
            (petshop.overriding(single<ChipRegistry> { FakeRegistry() }).onAFreshDatabase() + scraped).subgraph<Scraped>()
        }
        val scrape = When("Prometheus scrapes it") { testApp(service) { app: Scraped -> app.registry.scrape() } }
        Then("the JVM's meters are there, under the names a JVM dashboard reads") {
            scrape shouldContain "jvm_memory_used_bytes"
            scrape shouldContain "jvm_threads_live_threads"
            scrape shouldContain "process_cpu_usage"
        }
        And("so is the pool") { scrape shouldContain """hikaricp_connections_active{pool="petshop"""" }
        And("the shop is ready, and each check answers") {
            scrape shouldContain "petshop_ready 1.0"
            scrape shouldContain """petshop_health_check{check="database",} 1.0"""
            scrape shouldContain """petshop_health_check{check="shop",} 1.0"""
        }
    }
}
