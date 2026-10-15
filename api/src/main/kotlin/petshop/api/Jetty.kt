package petshop.api

import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.jetty.request
import io.opentelemetry.context.propagation.TextMapGetter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Without it the tracing filter extracts nothing, and a caller's `traceparent` starts a new trace. */
internal val requestHeaders = object : TextMapGetter<Params> {
    override fun keys(carrier: Params) = carrier.request.headers.fieldNamesCollection.map { it.lowercase() }

    override fun get(carrier: Params?, key: String): String? = carrier?.request?.headers?.get(key)
}

/** Run on [streams], since Jetty pulls a `Sequence` and has no stream engine of its own. */
internal fun <T : Any> Stream<Nothing, T>.pulledOn(streams: StreamBackend): Sequence<T> =
    Sequence { Pulled(this, streams) }

/**
 * A run of [stream], pulled one element at a time. Pelican's Jetty writer closes the iterator however the response
 * ends, which stops the run; a put blocked on a full queue is what holds a slow reader's stream back.
 */
private class Pulled<T : Any>(stream: Stream<Nothing, T>, streams: StreamBackend) : Iterator<T>, AutoCloseable {
    private val handed = ArrayBlockingQueue<Any>(HELD)
    private val closed = AtomicBoolean(false)
    private var next: Any? = null

    private val running: Running<Nothing, Unit> =
        stream.runFold(Unit) { _, element -> hand(element) }.start(streams).also { run ->
            run.exit.whenComplete { exit, failure -> hand(Ended(failure ?: (exit as? Exit.Died)?.cause)) }
        }

    /** Checked before each put, and [close] empties the queue after setting it, so a put never outlives the reader. */
    private fun hand(element: Any) {
        if (!closed.get()) handed.put(element)
    }

    override fun hasNext(): Boolean {
        if (next == null) next = handed.take()
        val ended = next as? Ended ?: return true
        ended.cause?.let { throw it }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        return (next as T).also { next = null }
    }

    override fun close() {
        closed.set(true)
        running.stop()
        handed.clear()
    }

    private class Ended(val cause: Throwable?)

    private companion object {
        const val HELD = 16
    }
}
