package petshop.app

import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.runCollect
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.stream.take
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import petshop.domain.Pet
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.Species
import java.util.concurrent.TimeUnit

private fun arrived(seq: Long) = PetArrived(seq, Pet(PetId(seq), "Pet $seq", Species.Cat))

/** The bus the shop publishes to, on its own: what it holds, what it refuses, and when a reader stops. */
class BusSpec {

    private val forks = Forks()

    @Test
    fun `what is published before anyone subscribes goes to the first subscriber, in order`() {
        val bus = QueueBus()
        bus.publish(arrived(1))
        bus.publish(arrived(2))

        val read = bus.subscribe().take(3).runCollect().start(forks)
        bus.publish(arrived(3))

        read.exit.toCompletableFuture().get(5, TimeUnit.SECONDS) shouldBe
            Exit.Done(listOf(arrived(1), arrived(2), arrived(3)))
    }

    @Test
    fun `a full queue turns an event away rather than dropping one, and a closed bus refuses them all`() {
        val bus = QueueBus(capacity = 2)

        bus.publish(arrived(1)) shouldBe arrived(1).right()
        bus.publish(arrived(2)) shouldBe arrived(2).right()
        withClue("nobody has read yet, and the queue holds two") {
            bus.publish(arrived(3)) shouldBe BusRefused(3, "full").left()
        }

        bus.close()
        bus.publish(arrived(4)) shouldBe BusRefused(4, "closed").left()
    }

    @Test
    fun `closing the bus ends a subscription, with everything published before it read`() {
        val bus = QueueBus()
        val read = bus.subscribe().runCollect().start(forks)
        bus.publish(arrived(1))

        bus.close()

        read.exit.toCompletableFuture().get(5, TimeUnit.SECONDS) shouldBe Exit.Done(listOf(arrived(1)))
    }

    @Test
    fun `a subscription waiting for its next event ends at once when its run is stopped`() {
        val read = QueueBus().subscribe().runCollect().start(forks)

        read.stop()

        read.exit.toCompletableFuture().get(5, TimeUnit.SECONDS) shouldBe Exit.Done(emptyList())
    }
}
