package petshop.load

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.junit5.LoadTest
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.report.writeHtmlReport
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.extension.RegisterExtension
import petshop.api.adoptPet
import petshop.api.listPets
import petshop.app.RegistrySettings
import petshop.app.petshop
import petshop.domain.AlreadyAdopted
import petshop.registry.ChipRecord
import petshop.registry.lookupChip
import petshop.registry.recordKeeper
import petshop.wiremock.PelicanWireMock

/**
 * The whole application under load, started by its own graph in this process: the actor, the arrivals
 * stream and the endpoints, exactly as `main` starts them. Nothing of the shop's is stubbed, and the
 * graph is given back when the block returns. The chip registry is somebody else's service, so it is
 * a WireMock server that knows every chip — the one node that differs from `main`.
 *
 * The load runs through Pelican's own typed client, so no URL appears in this file at all: a step
 * names the endpoint it calls, and what it expects back is the failure the endpoint declared rather
 * than a status code. A renamed route or a changed error moves the load with it, at compile time.
 */
class PetshopLoadTest {

    /** Every pet has a chip and every keeper is recorded: the registry is not what this measures. */
    @JvmField
    @RegisterExtension
    val registry = PelicanWireMock().apply {
        stub(lookupChip) { petId -> ok(ChipRecord("98100000000000$petId", keeper = "Petshop")) }
        stub(recordKeeper) { (number, keeper) -> ok(ChipRecord(number, keeper.keeper)) }
    }

    private val theShop: Module =
        petshop.overriding(single<RegistrySettings> { RegistrySettings(registry.baseUrl, 2.seconds) })

    private val browse = step("browse the shop")

    private val adoptTaken = step("adopt a pet somebody already has")

    @LoadTest
    fun `browsing holds up at two hundred a second`(proofload: Proofload) {
        val outcome = theShop.use { server: PelicanServer ->
            apiClient(server.baseUrl, JacksonCodecs).use { client ->
                // `response` rather than `call`: decoding every catalogue into a `List<Pet>` would put
                // the generator's own Jackson time inside a number that is about the shop.
                val shopping = scenario("browsing") {
                    exec(browse) { step ->
                        if (!client.response(listPets, Unit).isSuccess) step.fail("the shop did not answer")
                    }
                }

                proofload.run(shopping.at(200.perSecond, over = 10.seconds))
            }
        }

        val result = outcome.shouldBeRight()

        result.writeHtmlReport(Path.of("build/reports/proofload/browsing.html"))
        result.failed shouldBe 0L
        result[browse].responseTime.p99 shouldBeLessThan 250.milliseconds
    }

    /**
     * The claim the actor exists for, checked under load rather than in a unit test: a pet is sold
     * once. The step expects the declared `AlreadyAdopted`, so a single Ok — two people told they got
     * the same tortoise — fails the run, and so does anything the endpoint never declared.
     */
    @LoadTest
    fun `a pet already adopted is never sold again`(proofload: Proofload) {
        val outcome = theShop.use { server: PelicanServer ->
            apiClient(server.baseUrl, JacksonCodecs).use { client ->
                // The first adoption is the setup, and it has to have worked: a rush against a pet
                // nobody took would answer 200s and never reach the claim.
                client.outcome(adoptPet, 1L).shouldBeOk()

                val rush = scenario("the rush") {
                    exec(adoptTaken) { step ->
                        when (val answer = client.outcome(adoptPet, 1L)) {
                            is Outcome.Ok -> step.fail("the tortoise was sold twice")
                            is Outcome.Err ->
                                if (answer.error !is AlreadyAdopted) step.fail("not the declared failure")
                        }
                    }
                }

                proofload.run(rush.at(200.perSecond, over = 10.seconds))
            }
        }

        val result = outcome.shouldBeRight()

        result.writeHtmlReport(Path.of("build/reports/proofload/the-rush.html"))
        withClue("every one of them was told the tortoise was gone") { result.failed shouldBe 0L }
        result[adoptTaken].count shouldBe 2000L
    }
}
