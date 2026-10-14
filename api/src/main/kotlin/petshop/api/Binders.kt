package petshop.api

import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.stream.toSource
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.StreamOf
import io.github.matthewjones372.pelican.jetty.handledNow as jettyNow
import io.github.matthewjones372.pelican.jetty.handledOrFail as jettyOrFail
import io.github.matthewjones372.pelican.jetty.request as jettyRequest
import io.github.matthewjones372.pelican.jetty.streamedNow as jettyStreamed
import io.github.matthewjones372.pelican.pekko.handledNow as pekkoNow
import io.github.matthewjones372.pelican.pekko.handledOrFail as pekkoOrFail
import io.github.matthewjones372.pelican.pekko.request as pekkoRequest
import io.github.matthewjones372.pelican.pekko.streamedNow as pekkoStreamed
import io.opentelemetry.context.propagation.TextMapGetter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * pelican-jetty and pelican-pekko bind under the same names from different packages, and a stream is a `Sequence` on
 * one and a `Source` on the other, so the handlers are written once against this and bound by whichever serves.
 */
interface Binders {
    infix fun <I, T : Any> Endpoint<I, T>.handledNow(f: (I) -> T): ServerEndpoint

    infix fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledOrFail(f: (I) -> Outcome<E, T>): ServerEndpoint

    infix fun <I, T : Any> Endpoint<I, StreamOf<T>>.streamedNow(f: (I) -> Stream<Nothing, T>): ServerEndpoint

    /** Without it the tracing filter extracts nothing, and a caller's `traceparent` starts a new trace. */
    val headers: TextMapGetter<Params>
}

object PekkoBinders : Binders {
    override fun <I, T : Any> Endpoint<I, T>.handledNow(f: (I) -> T): ServerEndpoint = this pekkoNow { f(it) }

    override fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledOrFail(
        f: (I) -> Outcome<E, T>,
    ): ServerEndpoint = this pekkoOrFail { f(it) }

    override fun <I, T : Any> Endpoint<I, StreamOf<T>>.streamedNow(f: (I) -> Stream<Nothing, T>): ServerEndpoint =
        this pekkoStreamed { f(it).toSource() }

    override val headers = object : TextMapGetter<Params> {
        override fun keys(carrier: Params) = carrier.pekkoRequest.headers.map { it.lowercaseName() }

        override fun get(carrier: Params?, key: String): String? =
            carrier?.pekkoRequest?.getHeader(key)?.map { it.value() }?.orElse(null)
    }
}

/** A stream answered on Jetty is run on [streams], since Jetty pulls a `Sequence` and has no stream engine of its own. */
class JettyBinders(private val streams: StreamBackend) : Binders {
    override fun <I, T : Any> Endpoint<I, T>.handledNow(f: (I) -> T): ServerEndpoint = this jettyNow { f(it) }

    override fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledOrFail(
        f: (I) -> Outcome<E, T>,
    ): ServerEndpoint = this jettyOrFail { f(it) }

    override fun <I, T : Any> Endpoint<I, StreamOf<T>>.streamedNow(f: (I) -> Stream<Nothing, T>): ServerEndpoint =
        this jettyStreamed { input -> Sequence { Pulled(f(input), streams) } }

    override val headers = object : TextMapGetter<Params> {
        override fun keys(carrier: Params) = carrier.jettyRequest.headers.fieldNamesCollection.map { it.lowercase() }

        override fun get(carrier: Params?, key: String): String? = carrier?.jettyRequest?.headers?.get(key)
    }
}

/**
 * A run of [stream], pulled one element at a time. Pelican's Jetty writer closes the iterator however the response
 * ends, which stops the run; a put blocked on a full queue is what holds a slow reader's stream back, as Pekko's
 * demand does.
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
