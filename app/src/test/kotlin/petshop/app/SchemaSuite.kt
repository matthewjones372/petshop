package petshop.app

import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.story
import org.apache.avro.Schema
import org.apache.avro.SchemaCompatibility
import petshop.domain.OnShelf
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetReturned
import petshop.domain.Species

object SchemaSuite : Suite() {
    private val golden: Schema =
        Schema.Parser().parse(checkNotNull(javaClass.getResourceAsStream("/golden/shop-event.avsc")))

    init {
        test("the schema the wire classes derive is the one committed") {
            story {
                Then(
                    "it equals golden/shop-event.avsc; regenerate the file from shopEventSchema if the change is meant",
                ) {
                    shopEventSchema shouldBe golden
                }
            }
        }

        test("a reader on the committed schema can read what the shop writes") {
            story {
                val compatibility = When("a reader on the committed schema is checked against what the shop writes") {
                    SchemaCompatibility.checkReaderWriterCompatibility(golden, shopEventSchema).type
                }
                Then("it can read it") { compatibility shouldBe SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE }
            }
        }

        test("a return from the topic's history reads back as a return") {
            story {
                val returned = Given("Nibbles' return, from before ADR 0001") {
                    PetReturned(seq = 3, pet = Pet(PetId(1), "Nibbles", Species.Tortoise, OnShelf))
                }
                Then(
                    "the wire carries it and reads it back unchanged",
                ) { returned.toWire().toDomain() shouldBe returned }
            }
        }
    }
}
