package petshop.app

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/** What the story prototype promises: Lark specs 0115 and 0116's "done when", checked here first. */
class StorySpec {

    @Test
    fun `a value one step answers is what the next step is given`() {
        story {
            val pets = Given("a shop with three pets") { listOf("Nibbles", "Barnaby", "Mrs Peel") }
            val taken = When("Ada adopts the first") { pets.first() }
            Then("Ada has Nibbles") { taken shouldBe "Nibbles" }
        }
    }

    @Test
    fun `a story failing at its third step says the first two passed and what the third said`() {
        val failed = shouldThrow<StoryFailed> {
            story("adopting") {
                Given("a shop") { }
                When("Ada adopts Nibbles") { }
                Then("Nibbles is hers") { "Bea" shouldBe "Ada" }
                And("never reached") { }
            }
        }

        val lines = failed.message!!.lines()
        lines[0] shouldBe "Story: adopting"
        lines[1] shouldContain "✓ Given a shop"
        lines[2] shouldContain "✓ When Ada adopts Nibbles"
        lines[3] shouldContain "✗ Then Nibbles is hers"
        failed.message!! shouldContain "expected:<Ada> but was:<Bea>"
        failed.message!! shouldNotContain "never reached"
        failed.cause!!.message!! shouldContain "expected:<Ada>"
    }

    @Test
    fun `a failure is told once, under the innermost step that saw it`() {
        val failed = shouldThrow<StoryFailed> {
            story("nested") {
                Given("the service") { And("its database") { error("refused") } }
            }
        }

        val lines = failed.message!!.lines()
        lines[1] shouldContain "✗ Given the service"
        lines[2] shouldContain "    ✗ And its database"
        lines.count { "refused" in it } shouldBe 1
    }

    @Test
    fun `eventually waits until its block stops throwing, and says how many tries it took`() {
        var asked = 0
        val failed = shouldThrow<StoryFailed> {
            story("waiting") {
                Then("it gets there").eventually(within = 1_000.milliseconds, every = 1.milliseconds) {
                    asked++
                    check(asked >= 3) { "not yet" }
                }
                And("this fails, to see the transcript") { error("stop") }
            }
        }

        failed.message!! shouldContain "3 tries"
    }

    @Test
    fun `eventually gives up on time, and rethrows the last failure`() {
        val last = IllegalStateException("still 3 in the outbox")
        lateinit var thrown: StoryFailed
        val took = measureTime {
            thrown = shouldThrow<StoryFailed> {
                story("giving up") {
                    Then("the outbox drains").eventually(within = 200.milliseconds, every = 10.milliseconds) { throw last }
                }
            }
        }

        thrown.cause shouldBeSameInstanceAs last
        thrown.message!! shouldContain "still 3 in the outbox"
        (took < 2_000.milliseconds) shouldBe true
    }

    @Test
    fun `the failure message holds no escape codes, whatever the console gets`() {
        val failed = shouldThrow<StoryFailed> { story("plain") { Then("it fails") { error("no") } } }

        failed.message!! shouldNotContain "\u001B["
    }

    @Test
    fun `colour is off unless something asks for it, and an explicit property wins`() {
        Colour.decide(property = null, noColour = null, forceColour = null, underIntelliJ = false) shouldBe false
        Colour.decide(property = null, noColour = null, forceColour = null, underIntelliJ = true) shouldBe true
        Colour.decide(property = null, noColour = null, forceColour = "1", underIntelliJ = false) shouldBe true
        Colour.decide(property = null, noColour = "1", forceColour = "1", underIntelliJ = true) shouldBe false
        Colour.decide(property = "always", noColour = "1", forceColour = null, underIntelliJ = false) shouldBe true
        Colour.decide(property = "never", noColour = null, forceColour = "1", underIntelliJ = true) shouldBe false
    }

    @Test
    fun `a story takes its title from the test that tells it`() {
        val failed = shouldThrow<StoryFailed> { story { Then("it fails") { error("no") } } }

        failed.message!!.lines()[0] shouldBe "Story: a story takes its title from the test that tells it"
    }
}
