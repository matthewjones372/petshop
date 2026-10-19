package petshop.app

import arrow.core.Either
import io.github.matthewjones372.lark.Carriers
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.closingOf
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.stream.Hub
import io.github.matthewjones372.lark.stream.Run
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.runFold
import petshop.domain.ShopEvent

interface EventBus {

    /** Sends what the caller has bound (`Carriers.capture()`) with the event. */
    fun publish(event: ShopEvent): Either<BusRefused, ShopEvent>

    /**
     * Each event is handled inside what its publisher had bound, so [each] continues its trace. A bus that remembers
     * offsets moves one on only after [each] returns.
     */
    fun consume(each: (ShopEvent) -> Unit): Run<Nothing, Long>
}

/** The event is still in the outbox, so a refusal means "not yet", not "lost". */
data class BusRefused(val seq: Long, val why: String)

/** Refuses when a reader's queue is full or after close. Events published before any reader are held for the first. */
class HubBus(private val hub: Hub<Claimed>) : EventBus {

    override fun publish(event: ShopEvent): Either<BusRefused, ShopEvent> = hub.publish(
        Claimed(event, Carriers.capture()),
    ).mapLeft { refused -> BusRefused(event.seq, "$refused") }.map { event }

    override fun consume(each: (ShopEvent) -> Unit): Run<Nothing, Long> = hub.subscribe()
        .map { (event, carried) ->
            Carriers.within(carried) { each(event) }
            1L
        }
        .runFold(0L, Long::plus)
}

/** The hub, released by the graph after the bus that publishes to it. */
fun hubBus(capacity: Int = 256): Module =
    closingOf { -> Hub<Claimed>(capacity) } + singleOf(::HubBus).boundTo<EventBus>()
