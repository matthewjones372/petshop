package petshop.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.from
import petshop.domain.ShopEvent
import java.util.concurrent.LinkedBlockingQueue

/** Where the shop's events go once they have left it, and where anyone else reads them from. */
interface EventBus {

    fun publish(event: ShopEvent): Either<BusRefused, ShopEvent>

    fun subscribe(): Stream<Nothing, ShopEvent>
}

/** The bus said no. The event is still in the outbox, so no is "not yet" rather than "lost". */
data class BusRefused(val seq: Long, val why: String)

/**
 * A bus in the same process: a bounded queue for each subscriber. It stands where a broker would, and
 * it refuses the way one does — when a subscriber's queue is full, and after it has closed.
 *
 * It holds what is published before anyone subscribes rather than broadcasting to nobody, and hands it
 * to the first subscriber; past that it holds back rather than dropping, so a full queue is the only
 * place an event is turned away. A subscription is a stream with no backend in it: pulled on Forks, the
 * wait for the next event is the pull loop's own, and a stop's interrupt ends it.
 */
class QueueBus(private val capacity: Int = 256) : EventBus, AutoCloseable {

    /** What a subscription's queue holds after its last event, so that its stream ends. */
    private object Closed

    private val lock = Any()
    private val early = ArrayDeque<ShopEvent>()
    private val subscribers = mutableListOf<LinkedBlockingQueue<Any>>()
    private var closed = false

    override fun publish(event: ShopEvent): Either<BusRefused, ShopEvent> =
        synchronized(lock) {
            when {
                closed -> BusRefused(event.seq, "closed").left()
                subscribers.isEmpty() && early.size >= capacity -> BusRefused(event.seq, "full").left()
                subscribers.isEmpty() -> event.right().also { early.addLast(event) }
                subscribers.any { it.size >= capacity } -> BusRefused(event.seq, "full").left()
                else -> event.right().also { subscribers.forEach { queue -> queue.put(event) } }
            }
        }

    override fun subscribe(): Stream<Nothing, ShopEvent> {
        val queue = LinkedBlockingQueue<Any>()
        synchronized(lock) {
            // The first subscriber is handed what was published before it; later ones start from now.
            if (subscribers.isEmpty()) early.forEach(queue::put)
            early.clear()
            if (closed) queue.put(Closed)
            subscribers += queue
        }
        return Stream.from(Iterable { Delivered(queue) })
    }

    override fun close() = synchronized(lock) {
        closed = true
        subscribers.forEach { it.put(Closed) }
    }

    /** A subscription's events as they arrive: `hasNext` waits for the next one, or for the bus to close. */
    private class Delivered(private val queue: LinkedBlockingQueue<Any>) : Iterator<ShopEvent> {
        private var next: Any? = null

        override fun hasNext(): Boolean {
            val waiting = next ?: queue.take().also { next = it }
            return waiting !== Closed
        }

        override fun next(): ShopEvent {
            if (!hasNext()) throw NoSuchElementException("the bus has closed")
            return (next as ShopEvent).also { next = null }
        }
    }
}
