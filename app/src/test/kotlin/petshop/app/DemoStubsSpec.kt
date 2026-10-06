package petshop.app

import io.github.matthewjones372.lark.test.story
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.test.wiremock.stubFile
import java.nio.file.Path
import org.junit.jupiter.api.Test
import petshop.registry.ChipRecord
import petshop.registry.NewKeeper
import petshop.registry.Problem
import petshop.registry.lookupChip
import petshop.registry.noSuchChip
import petshop.registry.recordKeeper

/**
 * The demo's registry is a WireMock reading `demo/registry/mappings`, and its stubs are written here, in the
 * registry's endpoints, rather than by hand. So the demo answers what the contract says: a contract change that
 * moves an answer fails this test until the file is rewritten with `-Dpelican.golden.update=true`.
 *
 * Recording a keeper answers with the keeper from the request body, which the example lets Pelican find and
 * template (Pelican spec 0067).
 */
class DemoStubsSpec {

    @Test
    fun `the demo registry's stubs are the contract's`() = story {
        val stubs = Given("every pet chipped, except pet 3, and a new keeper recorded as asked") {
            stubFile(JacksonCodecs) {
                stub(lookupChip) { petId -> ok(ChipRecord("98100000000$petId", keeper = "Petshop")) }
                stub(lookupChip, 3L) answers noSuchChip(Problem("The registry has no chip under that id"))
                stub(recordKeeper, example = In2("981000000001", NewKeeper("Ada"))) { (number, asked) ->
                    ok(ChipRecord(number, asked.keeper))
                }
            }
        }
        Then("the demo's mapping file says the same") { stubs.writeTo(demoMappings) }
    }

    // Tests run in the module's directory, under Gradle and IntelliJ both.
    private val demoMappings = Path.of("..", "demo", "registry", "mappings")
}
