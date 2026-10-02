package petshop.app

import io.kotest.matchers.shouldBe
import org.apache.avro.Schema
import org.apache.avro.SchemaCompatibility
import org.junit.jupiter.api.Test

/**
 * The events' Avro schema is the shop's contract with whoever reads the topic, so it is a file in the
 * repository: a change to the wire classes is a change to this file, reviewed as one, and a change a
 * reader of the old schema could not follow fails here before any record is written with it.
 */
class SchemaSpec {

    private val golden: Schema =
        Schema.Parser().parse(checkNotNull(javaClass.getResourceAsStream("/golden/shop-event.avsc")))

    @Test
    fun `the schema the wire classes derive is the one committed`() = story {
        Then("it equals golden/shop-event.avsc; regenerate the file from shopEventSchema if the change is meant") {
            shopEventSchema shouldBe golden
        }
    }

    @Test
    fun `a reader on the committed schema can read what the shop writes`() = story {
        val compatibility = When("a reader on the committed schema is checked against what the shop writes") {
            SchemaCompatibility.checkReaderWriterCompatibility(golden, shopEventSchema).type
        }
        Then("it can read it") { compatibility shouldBe SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE }
    }
}
