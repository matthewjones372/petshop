package petshop.app

import io.github.matthewjones372.surefold.LogLevel
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.capturingLogs
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldContain
import io.github.matthewjones372.surefold.junit.shouldContainKey
import io.github.matthewjones372.surefold.junit.story
import petshop.domain.PetId
import petshop.domain.PetShop

object AdoptionLogSuite : Suite() {
    private val settled: Module = shopWith(FakeRegistry())

    init {
        test("an adoption names the pet and the adopter in pairs, not in prose") {
            story {
                val lines = When("Ada adopts Nibbles, with the log captured") {
                    capturingLogs { logs ->
                        testApp<PetShop, Unit>(settled) { shop -> shop.adopt(PetId(1), "Ada") }
                        logs.all()
                    }
                }
                val adopted =
                    Then("one line says Nibbles was adopted") { lines.single { it.message.contains("adopted") } }
                And("it names the pet and the adopter as pairs") {
                    adopted.annotations["pet_id"] shouldBe "1"
                    adopted.annotations["adopted_by"] shouldBe "Ada"
                }
                And("the span it was written in adds how long it had been running") {
                    adopted.annotations shouldContainKey "adopt_ms"
                }
            }
        }

        test("an adoption that cannot happen is a warning saying why") {
            story {
                val lines = When("Ada asks for a pet the shop never had, with the log captured") {
                    capturingLogs { logs ->
                        testApp<PetShop, Unit>(settled) { shop -> shop.adopt(PetId(404), "Ada") }
                        logs.all()
                    }
                }
                val refused = Then("one line is a warning") { lines.single { it.level == LogLevel.Warn } }
                And("it says why") { refused.message shouldContain "No pet 404" }
                And("the pairs are on the refusal too, or a search for the pet finds half its story") {
                    refused.annotations["pet_id"] shouldBe "404"
                }
            }
        }
    }
}
