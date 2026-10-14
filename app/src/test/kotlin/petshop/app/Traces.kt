package petshop.app

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState

/** A trace id no span in the shop starts by chance, so seeing it means the trace was carried. */
const val A_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"

/** [block] inside a sampled span of [A_TRACE], as a request arriving with a `traceparent` would be. */
fun <A> inATrace(block: () -> A): A =
    Span.wrap(SpanContext.create(A_TRACE, "00f067aa0ba902b7", TraceFlags.getSampled(), TraceState.getDefault()))
        .makeCurrent()
        .use { block() }

val currentTrace: String get() = Span.current().spanContext.traceId

/** The trace id in a W3C `traceparent`: `00-<trace>-<span>-<flags>`. */
fun traceOf(traceparent: String?): String? = traceparent?.split("-")?.getOrNull(1)
