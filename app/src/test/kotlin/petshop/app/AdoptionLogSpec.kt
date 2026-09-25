package petshop.app

import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.capturingLogs
import io.kotest.assertions.withClue
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import petshop.domain.PetId
import petshop.domain.PetShop

/**
 * What the service says about an adoption, and what a line carries besides its words.
 *
 * The annotations are the half worth asserting: a message reads the same either way, and only the
 * pairs reach a field search.
 */
class AdoptionLogSpec {

    private val settled: Module = shopWith(FakeRegistry())

    @Test
    fun `an adoption names the pet and the adopter in pairs, not in prose`() {
        val lines = capturingLogs { logs ->
            testApp<PetShop, Unit>(settled) { shop -> shop.adopt(PetId(1), "Ada"); Unit }
            logs.all()
        }

        val adopted = lines.single { it.message.contains("adopted") }

        adopted.annotations["pet_id"] shouldBe "1"
        adopted.annotations["adopted_by"] shouldBe "Ada"
        withClue("a span puts how long it has been running on every line written inside it") {
            adopted.annotations shouldContainKey "adopt_ms"
        }
    }

    @Test
    fun `an adoption that cannot happen is a warning saying why`() {
        val lines = capturingLogs { logs ->
            testApp<PetShop, Unit>(settled) { shop -> shop.adopt(PetId(404), "Ada"); Unit }
            logs.all()
        }

        val refused = lines.single { it.level == LogLevel.Warn }

        refused.message shouldContain "No pet 404"
        withClue("the pairs are on the refusal too, or a search for the pet finds half its story") {
            refused.annotations["pet_id"] shouldBe "404"
        }
    }
}
