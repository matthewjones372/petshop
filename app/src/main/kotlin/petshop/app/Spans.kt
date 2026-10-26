package petshop.app

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer

/**
 * A span with a kind and attributes, ended in error on a throw: surefold's `span` starts every span internal and
 * bare.
 */
internal fun <A> Tracer.span(
    name: String,
    kind: SpanKind,
    attributes: Attributes = Attributes.empty(),
    block: () -> A,
): A {
    val span = spanBuilder(name).setSpanKind(kind).setAllAttributes(attributes).startSpan()
    // Marked failed unless the block returns, so a throw ends the span in error without catching it here.
    var failed = true
    try {
        return span.makeCurrent().use { block() }.also { failed = false }
    } finally {
        if (failed) span.setStatus(StatusCode.ERROR)
        span.end()
    }
}

/** OpenTelemetry's messaging attributes, by their current names, which Estate follows a message by. */
internal object Messaging {
    val system: AttributeKey<String> = AttributeKey.stringKey("messaging.system")
    val operation: AttributeKey<String> = AttributeKey.stringKey("messaging.operation.type")
    val destination: AttributeKey<String> = AttributeKey.stringKey("messaging.destination.name")
    val partition: AttributeKey<String> = AttributeKey.stringKey("messaging.destination.partition.id")
    val offset: AttributeKey<Long> = AttributeKey.longKey("messaging.kafka.offset")
    val key: AttributeKey<String> = AttributeKey.stringKey("messaging.kafka.message.key")
    val id: AttributeKey<String> = AttributeKey.stringKey("messaging.message.id")
    val group: AttributeKey<String> = AttributeKey.stringKey("messaging.consumer.group.name")
}
