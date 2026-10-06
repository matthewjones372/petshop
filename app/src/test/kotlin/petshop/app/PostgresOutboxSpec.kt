package petshop.app

import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.test.story
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.junit.jupiter.api.Test
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.PetReturned
import petshop.domain.ShopEvent
import petshop.domain.Species
import petshop.outbox.OutboxRow
import petshop.outbox.OutboxTable
import petshop.outbox.PetRow

private val nibbles = Pet(PetId(1), "Nibbles", Species.Tortoise)
private val barnaby = Pet(PetId(2), "Barnaby", Species.Dog)

/** The outbox and the pool under it, on a schema of its own. No actor, no relay, no bus. */
private fun onItsOwn(test: (Outbox) -> Unit) =
    testApp(petshop.onAFreshDatabase().subgraph<Outbox>()) { outbox: Outbox -> test(outbox) }

/** Everything still in the outbox, read by a claim that publishes nothing and so takes nothing. */
private fun Outbox.left(): List<ShopEvent> {
    val seen = mutableListOf<ShopEvent>()
    claim(1_000) { claimed -> seen += claimed; emptyList() }
    return seen
}

/** The outbox as a table, against a real Postgres: what the relay's claims can and cannot see. */
class PostgresOutboxSpec {

    @Test
    fun `Postgres numbers each event, and a claim reads them back oldest first`() = story {
        onItsOwn { outbox ->
            val recorded = When("Nibbles arrives, is adopted and is returned") {
                listOf(
                    outbox.record(nibbles) { seq -> PetArrived(seq, nibbles) },
                    outbox.record(nibbles.copy(adopted = true)) { seq -> PetAdopted(seq, nibbles.copy(adopted = true), by = "Ada") },
                    outbox.record(nibbles) { seq -> PetReturned(seq, nibbles) },
                )
            }
            Then("Postgres numbered them 1, 2 and 3: the seq is the table's, so it counts on across restarts") {
                recorded.map { it.seq } shouldBe listOf(1L, 2L, 3L)
            }
            And("a claim reads them back oldest first") { outbox.left() shouldBe recorded }
        }
    }

    @Test
    fun `what the bus took leaves the table, and what it refused stays for the next claim`() = story {
        onItsOwn { outbox ->
            val (arrived, adopted) = Given("Nibbles' arrival and adoption in the outbox") {
                outbox.record(nibbles) { seq -> PetArrived(seq, nibbles) } to
                    outbox.record(nibbles.copy(adopted = true)) { seq -> PetAdopted(seq, nibbles.copy(adopted = true), by = "Ada") }
            }
            val taken = When("a claim's bus takes the adoption and refuses the arrival") {
                outbox.claim(10) { claimed -> claimed.filter { it.seq == adopted.seq } }
            }
            Then("the claim answers what was taken") { taken shouldBe listOf(adopted) }
            And("only the refused arrival is left for the next claim") { outbox.left() shouldBe listOf(arrived) }
        }
    }

    @Test
    fun `a claim that throws halfway takes nothing`() = story {
        onItsOwn { outbox ->
            val arrived = Given("Nibbles' arrival in the outbox") { outbox.record(nibbles) { seq -> PetArrived(seq, nibbles) } }
            When("a claim falls over after publishing") {
                shouldThrow<IllegalStateException> { outbox.claim(10) { error("the process fell over after publishing") } }
            }
            Then("the claim rolled back, so the event goes again: at least once, not at most") {
                outbox.left() shouldBe listOf(arrived)
            }
        }
    }

    @Test
    fun `a second claim skips the rows the first is holding, rather than waiting for them`() = story {
        onItsOwn { outbox ->
            val (first, second) = Given("two of Nibbles' arrivals, then two of Barnaby's") {
                (1..2).map { outbox.record(nibbles) { seq -> PetArrived(seq, nibbles) } } to
                    (1..2).map { outbox.record(barnaby) { seq -> PetArrived(seq, barnaby) } }
            }

            val holding = CountDownLatch(1)
            val letGo = CountDownLatch(1)
            val slow = When("one claim takes the oldest two and holds them") {
                CompletableFuture.supplyAsync {
                    outbox.claim(2) { claimed ->
                        holding.countDown()
                        letGo.await(10, TimeUnit.SECONDS)
                        claimed
                    }
                }.also { holding.await(10, TimeUnit.SECONDS) shouldBe true }
            }
            val seenBeside = mutableListOf<ShopEvent>()
            // On a thread of its own with a deadline, so a claim that waited on the first one's locks fails
            // the test rather than hanging it.
            val beside = And("a second claim runs beside it") {
                CompletableFuture.supplyAsync {
                    outbox.claim(10) { claimed -> seenBeside += claimed; emptyList() }
                }.get(5, TimeUnit.SECONDS)
            }
            Then("the second saw only Barnaby's, and nothing waited: FOR UPDATE SKIP LOCKED") {
                seenBeside shouldBe second
                beside.shouldBeEmpty()
            }
            And("once the first lets go, it took Nibbles' and left Barnaby's") {
                letGo.countDown()
                slow.get(5, TimeUnit.SECONDS) shouldBe first
                outbox.left() shouldBe second
            }
        }
    }

    @Test
    fun `the shelf starts as the opening catalogue, and keeps every change written to it`() = story {
        onItsOwn { outbox ->
            val first = When("the shelf is read for the first time") { outbox.shelf(listOf(nibbles, barnaby)) }
            Then("it is the opening catalogue") { first shouldBe listOf(nibbles, barnaby) }
            When("Nibbles' adoption is recorded") {
                outbox.record(nibbles.copy(adopted = true)) { seq -> PetAdopted(seq, nibbles.copy(adopted = true), by = "Ada") }
            }
            Then("the shelf has her adopted, and the opening catalogue does not put her back") {
                outbox.shelf(listOf(nibbles, barnaby)) shouldBe listOf(nibbles.copy(adopted = true), barnaby)
            }
        }
    }

    @Test
    fun `a pet's change and its event are one transaction, so an event that fails leaves the pet as it was`() = story {
        testApp(petshop.onAFreshDatabase().subgraph<DataSource>()) { dataSource: DataSource ->
            val table = Given("a pets table with Nibbles on the shelf") {
                OutboxTable(dataSource).also { it.stock(listOf(PetRow(1, "Nibbles", "Tortoise", adopted = false))) }
            }
            When("her adoption is recorded with an event the outbox table refuses") {
                shouldThrow<Exception> {
                    table.record(
                        PetRow(1, "Nibbles", "Tortoise", adopted = true),
                        OutboxRow(0, "sold", 1, "Nibbles", "Tortoise", adopted = true, adoptedBy = "Ada"),
                    )
                }
            }
            Then("she is still on the shelf, because the pet's row rolled back with the event") {
                table.pets().single().adopted shouldBe false
            }
        }
    }
}
