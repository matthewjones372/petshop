package petshop.app

import io.github.matthewjones372.lark.Carriers
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.closingOf
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.stream.Hub
import io.github.matthewjones372.lark.stream.Stream
import petshop.api.ShopEventDto
import petshop.api.toDto
import petshop.domain.ShopEvent

/**
 * The events the projection counts, for whoever is watching now. Its own hub rather than the bus's: the bus may be
 * Kafka, and a second consumer there would share the projection's partitions instead of seeing every event.
 */
class Feed internal constructor(private val hub: Hub<ShopEventDto>) : AutoCloseable by hub {

    /**
     * Skipped with no watchers, since a hub holds what is published before its first subscriber and the first page
     * opened would replay the shop's opening minutes. A watcher too slow to keep up costs everyone that event.
     */
    fun offer(event: ShopEvent) {
        if (hub.subscribers == 0) return
        // The trace the event is handled in, so a page that shows it can continue that trace.
        val dto = event.toDto().copy(traceparent = Carriers.capture()["traceparent"])
        hub.publish(dto).onLeft { counter("petshop.feed.dropped").increment() }
    }

    val watchers: Int get() = hub.subscribers

    fun watch(): Stream<Nothing, ShopEventDto> = hub.subscribe()
}

val feed: Module = closingOf { -> Feed(Hub(capacity = 256)) }
