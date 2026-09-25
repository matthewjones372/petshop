package petshop.app

import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.PetReturned
import petshop.domain.ShopEvent
import petshop.domain.Species
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    fun `Postgres numbers each event, and a claim reads them back oldest first`() = onItsOwn { outbox ->
        val arrived = outbox.record { seq -> PetArrived(seq, nibbles) }
        val adopted = outbox.record { seq -> PetAdopted(seq, nibbles.copy(adopted = true), by = "Ada") }
        val returned = outbox.record { seq -> PetReturned(seq, nibbles) }

        withClue("the seq is the table's, so it counts on across restarts") {
            listOf(arrived.seq, adopted.seq, returned.seq) shouldBe listOf(1L, 2L, 3L)
        }
        outbox.left() shouldBe listOf(arrived, adopted, returned)
    }

    @Test
    fun `what the bus took leaves the table, and what it refused stays for the next claim`() = onItsOwn { outbox ->
        val arrived = outbox.record { seq -> PetArrived(seq, nibbles) }
        val adopted = outbox.record { seq -> PetAdopted(seq, nibbles.copy(adopted = true), by = "Ada") }

        val taken = outbox.claim(10) { claimed -> claimed.filter { it.seq == adopted.seq } }

        taken shouldBe listOf(adopted)
        outbox.left() shouldBe listOf(arrived)
    }

    @Test
    fun `a claim that throws halfway takes nothing`() = onItsOwn { outbox ->
        val arrived = outbox.record { seq -> PetArrived(seq, nibbles) }

        shouldThrow<IllegalStateException> {
            outbox.claim(10) { error("the process fell over after publishing") }
        }

        withClue("the claim rolled back, so the event goes again: at least once, not at most") {
            outbox.left() shouldBe listOf(arrived)
        }
    }

    @Test
    fun `a second claim skips the rows the first is holding, rather than waiting for them`() = onItsOwn { outbox ->
        val first = (1..2).map { outbox.record { seq -> PetArrived(seq, nibbles) } }
        val second = (1..2).map { outbox.record { seq -> PetArrived(seq, barnaby) } }

        val holding = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val slow = CompletableFuture.supplyAsync {
            outbox.claim(2) { claimed ->
                holding.countDown()
                letGo.await(10, TimeUnit.SECONDS)
                claimed
            }
        }
        holding.await(10, TimeUnit.SECONDS) shouldBe true

        val seenBeside = mutableListOf<ShopEvent>()
        // Run on a thread of its own with a deadline, so a claim that waited on the first one's locks
        // fails the test rather than hanging it.
        val beside = CompletableFuture.supplyAsync {
            outbox.claim(10) { claimed -> seenBeside += claimed; emptyList() }
        }.get(5, TimeUnit.SECONDS)

        withClue("FOR UPDATE SKIP LOCKED: the first claim's rows are not there to see, and nothing waited") {
            seenBeside shouldBe second
            beside.shouldBeEmpty()
        }

        letGo.countDown()
        slow.get(5, TimeUnit.SECONDS) shouldBe first
        outbox.left() shouldBe second
    }
}
