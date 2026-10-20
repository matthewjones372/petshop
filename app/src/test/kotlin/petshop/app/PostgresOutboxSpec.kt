package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.liquibase.Migrated
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.test.story
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import petshop.domain.Adopted
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

private val nibbles = Pet(PetId(1), "Nibbles", Species.Tortoise)
private val barnaby = Pet(PetId(2), "Barnaby", Species.Dog)

private fun onItsOwn(test: (Outbox) -> Unit) =
    testApp(Petshop.module.onAFreshDatabase().subgraph<Outbox>()) { outbox: Outbox -> test(outbox) }

/** Read by a claim that publishes nothing, so it takes nothing. */
private fun Outbox.left(): List<ShopEvent> {
    val seen = mutableListOf<ShopEvent>()
    claim(1_000) { claimed ->
        seen += claimed.map { it.event }
        emptyList()
    }
    return seen
}

class PostgresOutboxSpec {

    @Test
    fun `Postgres numbers each event, and a claim reads them back oldest first`() = story {
        onItsOwn { outbox ->
            val recorded = When("Nibbles arrives and is adopted, and Barnaby arrives") {
                listOf(
                    outbox.record(nibbles) { seq -> PetArrived(seq, nibbles) },
                    outbox.record(
                        nibbles.copy(standing = Adopted("Ada")),
                    ) { seq -> PetAdopted(seq, nibbles.copy(standing = Adopted("Ada")), by = "Ada") },
                    outbox.record(barnaby) { seq -> PetArrived(seq, barnaby) },
                )
            }
            Then("Postgres numbered them 1, 2 and 3: the seq is the table's, so it counts on across restarts") {
                recorded.map { it.seq } shouldBe listOf(1L, 2L, 3L)
            }
            And("a claim reads them back oldest first") { outbox.left() shouldBe recorded }
        }
    }

    @Test
    fun `a return written before the registry had to confirm adoptions is still claimed`() = story {
        onItsOwn { outbox ->
            val returned = Given("Nibbles' return, as the shop wrote it before ADR 0001, still unpublished") {
                outbox.record(nibbles) { seq -> PetReturned(seq, nibbles) }
            }
            Then("a claim reads it back as a return, so the upgrade does not stop the relay on it") {
                outbox.left() shouldBe listOf(returned)
            }
        }
    }

    @Test
    fun `what the bus took leaves the table, and what it refused stays for the next claim`() = story {
        onItsOwn { outbox ->
            val (arrived, adopted) = Given("Nibbles' arrival and adoption in the outbox") {
                outbox.record(nibbles) { seq -> PetArrived(seq, nibbles) } to
                    outbox.record(
                        nibbles.copy(standing = Adopted("Ada")),
                    ) { seq -> PetAdopted(seq, nibbles.copy(standing = Adopted("Ada")), by = "Ada") }
            }
            val taken = When("a claim's bus takes the adoption and refuses the arrival") {
                outbox.claim(10) { claimed -> claimed.map { it.event }.filter { it.seq == adopted.seq } }
            }
            Then("the claim answers what was taken") { taken shouldBe listOf(adopted) }
            And("only the refused arrival is left for the next claim") { outbox.left() shouldBe listOf(arrived) }
        }
    }

    @Test
    fun `an event keeps the trace it was recorded in, for the relay to publish it in`() = story {
        onItsOwn { outbox ->
            Given("Nibbles' arrival, recorded inside a trace") {
                inATrace { outbox.record(nibbles) { seq -> PetArrived(seq, nibbles) } }
            }
            val carried = When("a claim reads it back") {
                val seen = mutableListOf<Claimed>()
                outbox.claim(10) { claimed ->
                    seen += claimed
                    emptyList()
                }
                seen.single().carried
            }
            Then("it carries that trace") { traceOf(carried["traceparent"]) shouldBe A_TRACE }
        }
    }

    @Test
    fun `a claim that throws halfway takes nothing`() = story {
        onItsOwn { outbox ->
            val arrived = Given(
                "Nibbles' arrival in the outbox",
            ) { outbox.record(nibbles) { seq -> PetArrived(seq, nibbles) } }
            When("a claim falls over after publishing") {
                shouldThrow<IllegalStateException> {
                    outbox.claim(
                        10,
                    ) { error("the process fell over after publishing") }
                }
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
                        claimed.map { it.event }
                    }
                }.also { holding.await(10, TimeUnit.SECONDS) shouldBe true }
            }
            val seenBeside = mutableListOf<ShopEvent>()
            // On a thread of its own with a deadline, so a claim that waited on the first one's locks fails
            // the test rather than hanging it.
            val beside = And("a second claim runs beside it") {
                CompletableFuture.supplyAsync {
                    outbox.claim(10) { claimed ->
                        seenBeside += claimed.map { it.event }
                        emptyList()
                    }
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
                outbox.record(
                    nibbles.copy(standing = Adopted("Ada")),
                ) { seq -> PetAdopted(seq, nibbles.copy(standing = Adopted("Ada")), by = "Ada") }
            }
            Then("the shelf has her adopted, and the opening catalogue does not put her back") {
                outbox.shelf(listOf(nibbles, barnaby)) shouldBe listOf(nibbles.copy(standing = Adopted("Ada")), barnaby)
            }
        }
    }

    @Test
    fun `a pet's change and its event are one transaction, so an event that fails leaves the pet as it was`() = story {
        testApp(migrated) { (dataSource): Tables ->
            val table = Given("a pets table with Nibbles on the shelf") {
                OutboxTable(
                    dataSource,
                ).also { it.stock(listOf(PetRow(1, "Nibbles", "Tortoise", standing = "on_shelf", adoptedBy = null))) }
            }
            When("her adoption is recorded with an event the outbox table refuses") {
                shouldThrow<Exception> {
                    table.record(
                        PetRow(1, "Nibbles", "Tortoise", standing = "adopted", adoptedBy = "Ada"),
                        OutboxRow(0, "sold", 1, "Nibbles", "Tortoise", adopted = true, adoptedBy = "Ada"),
                    )
                }
            }
            Then("she is still on the shelf, because the pet's row rolled back with the event") {
                table.pets().single().standing shouldBe "on_shelf"
            }
        }
    }
}

/** The pool once the changelog has run, for a test that writes the tables without the outbox in front. */
private data class Tables(val dataSource: DataSource)

private val migrated: Module =
    (
        Petshop.module.onAFreshDatabase() + single { source: DataSource, _: Migrated ->
            Tables(
                source,
            )
        }
        ).subgraph<Tables>()
