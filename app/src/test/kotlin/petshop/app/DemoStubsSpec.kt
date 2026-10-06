package petshop.app

import io.github.matthewjones372.lark.test.story
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.test.wiremock.stubFile
import java.nio.file.Path
import org.junit.jupiter.api.Test
import petshop.registry.ChipRecord
import petshop.registry.Problem
import petshop.registry.lookupChip
import petshop.registry.noSuchChip

/**
 * The demo's registry is a WireMock reading `demo/registry/mappings`, and its chip lookups are written here, in
 * the registry's endpoints, rather than by hand. So the demo answers what the contract says: a contract change
 * that moves an answer fails this test until the file is rewritten with `-Dpelican.golden.update=true`.
 *
 * Recording a keeper stays hand-written, in `keeper.json`: its answer is built from the request body, and a
 * mapping file can only template an answer from the path (Pelican spec 0062).
 */
class DemoStubsSpec {

    @Test
    fun `the demo registry's chip lookups are the contract's`() = story {
        val stubs = Given("every pet chipped, except pet 3") {
            stubFile(JacksonCodecs) {
                stub(lookupChip) { petId -> ok(ChipRecord("98100000000$petId", keeper = "Petshop")) }
                stub(lookupChip, 3L) answers noSuchChip(Problem("The registry has no chip under that id"))
            }
        }
        Then("the demo's mapping file says the same") { stubs.writeTo(demoMappings) }
    }

    // Tests run in the module's directory, under Gradle and IntelliJ both.
    private val demoMappings = Path.of("..", "demo", "registry", "mappings")
}
