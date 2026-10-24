package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.liquibase.Migrated
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.test.story
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import petshop.outbox.OutboxRow
import petshop.outbox.OutboxTable
import petshop.outbox.PetRow
import javax.sql.DataSource

/** The outbox beside the table under it, for a test that writes a row the outbox would never write. */
private class Underneath(val outbox: Outbox, val table: OutboxTable)

private val underneath: Module =
    (
        Petshop.module.onAFreshDatabase() + single { source: DataSource, outbox: Outbox, _: Migrated ->
            Underneath(outbox, OutboxTable(source))
        }
        ).subgraph<Underneath>()

class CorruptOutboxRowSpec {

    @Test
    fun `an adoption row that names nobody stops the claim, rather than going out without an adopter`() = story {
        testApp(underneath) { under: Underneath ->
            Given("an adoption of Nibbles in the outbox with no adopter, which the table's checks let through") {
                under.table.record(
                    PetRow(1, "Nibbles", "Tortoise", standing = "adopted", adoptedBy = "Ada"),
                    OutboxRow(0, "adopted", 1, "Nibbles", "Tortoise", adopted = true, adoptedBy = null),
                )
            }
            val failure = When("the relay claims it") {
                shouldThrow<IllegalStateException> { under.outbox.claim(1_000) { claimed -> claimed.map { it.event } } }
            }
            Then("the claim says which row is wrong") { failure.message shouldBe "adoption 1 names nobody" }
            val kinds = And("the row stays for someone to mend") {
                var kinds: List<String> = emptyList()
                under.table.claim(1_000) { rows ->
                    kinds = rows.map { it.kind }
                    emptyList()
                }
                kinds
            }
            Then("it is the adoption") { kinds shouldBe listOf("adopted") }
        }
    }
}
