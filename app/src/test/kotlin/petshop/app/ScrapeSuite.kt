package petshop.app

import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.overriding
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.app.subgraph
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.alone
import io.github.matthewjones372.surefold.junit.forAll
import io.github.matthewjones372.surefold.junit.shouldContain
import io.github.matthewjones372.surefold.junit.story
import io.github.matthewjones372.surefold.metrics
import io.github.matthewjones372.surefold.micrometer.MicrometerMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import petshop.domain.ChipRegistry
import petshop.domain.PetId
import petshop.domain.PetShop
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

/** A registry of this test's own, not the global one, so tests running together cannot read each other's numbers. */
private class Scraped(val registry: PrometheusMeterRegistry)

private val scraped: Module =
    single { registry: PrometheusMeterRegistry, _: ReadinessGauges, _: JvmGcMetrics, _: PetShop, _: DataSource ->
        Scraped(
            registry,
        )
    }

private val settled: Module = shopWith(FakeRegistry())

private val checked = listOf(
    "petshop_ready 1.0",
    """petshop_health_check{check="database"} 1.0""",
    """petshop_health_check{check="shop"} 1.0""",
) + listOf("relay", "projection", "arrivals").map { run -> """petshop_health_check{check="$run"} 1.0""" }

object ScrapeSuite : Suite() {
    init {
        test("an adoption is a line in the scrape, with the outcome as a label") {
            story {
                val registry = Given(
                    "a Prometheus registry of this test's own",
                ) { PrometheusMeterRegistry(PrometheusConfig.DEFAULT) }
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

        test("an adoption the registry has yet to confirm is counted as pending") {
            story {
                val registry = Given(
                    "a Prometheus registry of this test's own",
                ) { PrometheusMeterRegistry(PrometheusConfig.DEFAULT) }
                val unconfirmed = shopWith(
                    ScriptedRegistry(
                        transfers = { ScriptedRegistry.Transfer.RecordsButLosesTheAnswer },
                        answers = { _, n -> n == 1 },
                    ),
                )
                val scrape = When("Ada adopts Nibbles, and the registry cannot be read back") {
                    metrics.locally(MicrometerMetrics(registry)) {
                        testApp<PetShop, Unit>(unconfirmed) { shop -> shop.adopt(PetId(1), "Ada") }
                        registry.scrape()
                    }
                }
                Then("the outcome says pending, not taken") {
                    scrape shouldContain """petshop_adoptions_total{outcome="pending""""
                }
            }
        }

        // Alone: every test here starts a pool named "petshop" on Micrometer's global registry, and one closing its
        // pool removes the shared hikaricp_* meters this test reads.
        test("the scrape says how the JVM, the pool and every health check are", alone) {
            // Measured where the shop measures outside a suite: surefold-junit binds each test a capture of its own.
            metrics.locally(MicrometerMetrics()) {
                story {
                    val service = Given("the shop on a fresh database") {
                        (
                            Petshop.module.overriding(
                                single<ChipRegistry> { FakeRegistry() },
                            ).onAFreshDatabase() + scraped
                            ).subgraph<Scraped>()
                    }
                    testApp(service) { app: Scraped ->
                        val scrape = When("Prometheus scrapes it") { app.registry.scrape() }
                        Then("the JVM's meters are there, under the names a JVM dashboard reads") {
                            scrape shouldContain "jvm_memory_used_bytes"
                            scrape shouldContain "jvm_threads_live_threads"
                            scrape shouldContain "process_cpu_usage"
                        }
                        And("so is the pool") {
                            scrape shouldContain """hikaricp_connections_active{pool="petshop""""
                        }
                        And("the shop is ready, and each check answers") {
                            // The checks are asked on their own thread, once a second, so the first answer is a
                            // moment behind.
                            val now = waitUntil(
                                10.seconds,
                                { now -> checked.all { it in now } },
                            ) { app.registry.scrape() }
                            checked.forAll { now shouldContain it }
                        }
                    }
                }
            }
        }
    }
}
