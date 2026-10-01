package petshop.app

import arrow.core.Either
import io.github.matthewjones372.lark.stream.Hub
import io.github.matthewjones372.lark.stream.Run
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.runFold
import petshop.domain.ShopEvent

/** Where the shop's events go once they have left it, and where anyone else reads them from. */
interface EventBus {

    fun publish(event: ShopEvent): Either<BusRefused, ShopEvent>

    /**
     * Every event handed to [each], one at a time, until the run is stopped. A bus that remembers where a
     * reader got to moves that on only once [each] has returned, so an event [each] was not given comes
     * round again; the run answers with how many it handed over.
     */
    fun consume(each: (ShopEvent) -> Unit): Run<Nothing, Long>
}

/** The bus said no. The event is still in the outbox, so no is "not yet" rather than "lost". */
data class BusRefused(val seq: Long, val why: String)

/**
 * A bus in the same process: Lark's [Hub], a queue for each reader. It stands where a broker would, and
 * it refuses the way one does: when a reader's queue is full, and after it has closed.
 *
 * What is published before anyone reads is held for the first reader, and a reader runs on whichever
 * backend runs its stream.
 */
class HubBus(capacity: Int = 256) : EventBus, AutoCloseable {

    private val hub = Hub<ShopEvent>(capacity)

    override fun publish(event: ShopEvent): Either<BusRefused, ShopEvent> =
        hub.publish(event).mapLeft { refused -> BusRefused(event.seq, "$refused") }

    override fun consume(each: (ShopEvent) -> Unit): Run<Nothing, Long> =
        hub.subscribe()
            .map { event ->
                each(event)
                1L
            }
            .runFold(0L, Long::plus)

    override fun close() = hub.close()
}
