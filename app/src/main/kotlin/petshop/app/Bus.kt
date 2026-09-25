package petshop.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.from
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.javadsl.BroadcastHub
import org.apache.pekko.stream.javadsl.Keep
import org.apache.pekko.stream.javadsl.Source
import petshop.domain.ShopEvent

/** Where the shop's events go once they have left it, and where anyone else reads them from. */
interface EventBus {

    fun publish(event: ShopEvent): Either<BusRefused, ShopEvent>

    fun subscribe(): Stream<Nothing, ShopEvent>
}

/** The bus said no. The event is still in the outbox, so no is "not yet" rather than "lost". */
data class BusRefused(val seq: Long, val why: String)

/**
 * A bus in the same process: a bounded queue into a broadcast hub. It stands where a broker would,
 * and it refuses the way one does — when it is full, and after it has closed.
 *
 * The hub waits for its first subscriber rather than broadcasting to nobody, and past that it holds
 * back rather than dropping, so a full queue is the only place an event is turned away.
 */
class HubBus(system: ActorSystem, capacity: Int = 256) : EventBus, AutoCloseable {

    private val queueAndHub =
        Source.queue<ShopEvent>(capacity)
            .toMat(BroadcastHub.of(ShopEvent::class.java, 1, capacity), Keep.both())
            .run(system)

    override fun publish(event: ShopEvent): Either<BusRefused, ShopEvent> {
        val offered = queueAndHub.first().offer(event)
        return if (offered.isEnqueued) event.right() else BusRefused(event.seq, "$offered").left()
    }

    override fun subscribe(): Stream<Nothing, ShopEvent> = Stream.from(queueAndHub.second())

    override fun close() = queueAndHub.first().complete()
}
