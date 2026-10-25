package petshop.app

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.story
import io.github.matthewjones372.lark.resource
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.test.wiremock.stubFile
import petshop.registry.ChipRecord
import petshop.registry.NewKeeper
import petshop.registry.Problem
import petshop.registry.lookupChip
import petshop.registry.noSuchChip
import petshop.registry.recordKeeper
import java.nio.file.Path

/**
 * The demo's WireMock reads `demo/registry/mappings`, written here from the registry's endpoints, so a contract change
 * that moves an answer fails this test until the file is rewritten with `-Dpelican.golden.update=true`.
 */
object DemoStubsSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    // Tests run in the module's directory, under Gradle and IntelliJ both.
    private val demoMappings = Path.of("..", "demo", "registry", "mappings")

    init {
        test("the demo registry's stubs are the contract's") { _ ->
            story {
                val stubs = Given("every pet chipped, except pet 3, and a new keeper recorded as asked") {
                    stubFile(KotlinxCodecs) {
                        stub(lookupChip) { petId -> ok(ChipRecord("98100000000$petId", keeper = "Petshop")) }
                        stub(lookupChip, 3L) answers noSuchChip(Problem("The registry has no chip under that id"))
                        // The example lets Pelican find the keeper in the body and template the answer from it
                        // (spec 0067).
                        stub(recordKeeper, example = In2("981000000001", NewKeeper("Ada"))) { (number, asked) ->
                            ok(ChipRecord(number, asked.keeper))
                        }
                    }
                }
                // writeTo is the check: it throws an AssertionError when the file on disk differs.
                Then("the demo's mapping file says the same") { stubs.writeTo(demoMappings) shouldBe Unit }
            }
        }
    }
}
