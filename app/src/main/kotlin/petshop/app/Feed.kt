package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.closingOf
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.stream.Hub
import io.github.matthewjones372.lark.stream.Stream
import petshop.domain.ShopEvent

/**
 * The events the projection counts, for whoever is watching now. Its own hub rather than the bus's: the bus may be
 * Kafka, and a second consumer there would share the projection's partitions instead of seeing every event.
 */
class Feed internal constructor(private val hub: Hub<ShopEvent>) : AutoCloseable by hub {

    /**
     * Skipped with no watchers, since a hub holds what is published before its first subscriber and the first page
     * opened would replay the shop's opening minutes. A watcher too slow to keep up costs everyone that event.
     */
    fun offer(event: ShopEvent) {
        if (hub.subscribers > 0) hub.publish(event).onLeft { counter("petshop.feed.dropped").increment() }
    }

    val watchers: Int get() = hub.subscribers

    fun watch(): Stream<Nothing, ShopEvent> = hub.subscribe()
}

val feed: Module = closingOf { -> Feed(Hub(capacity = 256)) }
