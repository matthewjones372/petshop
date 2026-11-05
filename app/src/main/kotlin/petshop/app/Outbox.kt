package petshop.app

import dev.surefold.Carriers
import dev.surefold.Schedule
import dev.surefold.app.Migrated
import dev.surefold.app.Module
import dev.surefold.app.boundTo
import dev.surefold.app.closingOf
import dev.surefold.app.single
import dev.surefold.counter
import dev.surefold.fail.fold
import dev.surefold.gauge
import dev.surefold.increment
import dev.surefold.logAnnotated
import dev.surefold.logInfo
import dev.surefold.logWarn
import dev.surefold.stream.Run
import dev.surefold.stream.Running
import dev.surefold.stream.Stream
import dev.surefold.stream.StreamBackend
import dev.surefold.stream.mapConcat
import dev.surefold.stream.mapPar
import dev.surefold.stream.restartOnDefect
import dev.surefold.stream.runFold
import dev.surefold.stream.start
import dev.surefold.stream.tick
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import petshop.domain.Pet
import petshop.domain.ShopEvent
import javax.sql.DataSource
import kotlin.time.Duration

/** A claim holds its rows from every other claim until it answers, so two relays never publish the same event. */
interface Outbox {

    /** Writes [pet] and its event, with the next `seq`, in one transaction. */
    fun record(pet: Pet, numbered: (seq: Long) -> ShopEvent): ShopEvent

    /** Writes [pet] with no event: a change nobody outside the shop hears about. */
    fun keep(pet: Pet)

    fun shelf(opening: List<Pet>): List<Pet>

    /** Unpublished events, oldest first, held while [publish] runs. Only what [publish] answers leaves the outbox. */
    fun claim(limit: Int, publish: (List<Claimed>) -> List<ShopEvent>): List<ShopEvent>
}

/** An event and what its write carried, so publishing it continues the trace it was recorded in. */
data class Claimed(val event: ShopEvent, val carried: Map<String, String> = emptyMap())

class OutboxRelay internal constructor(val running: Running<Nothing, Long>) : AutoCloseable by running

private const val batch = 100

/**
 * At least once, in recorded order except for a refused event, which goes again on a later tick.
 * Consumers deduplicate by `seq`.
 */
internal fun relay(
    every: Duration,
    outbox: Outbox,
    bus: EventBus,
    tracer: Tracer = OpenTelemetry.noop().getTracer("petshop"),
): Run<Nothing, Long> = Stream.tick(every = every, element = Unit)
    // mapPar gives the blocking claim its own virtual thread. Publishing happens inside the
    // claim because the rows are only held while it is open.
    .mapPar(1) { _ -> drained(outbox, bus, tracer) }
    .mapConcat { published -> published }
    // A throwing claim would otherwise stop the outbox draining. Restarting is safe: the claim
    // rolled back.
    .restartOnDefect(Schedule.spaced(every))
    .runFold(0L) { published, event ->
        logAnnotated("seq" to event.seq.toString()) {
            logInfo("published ${event::class.simpleName}")
        }
        counter("petshop.outbox.published").increment()
        published + 1
    }

/**
 * Claims batch after batch until one comes back short, so a tick drains what was recorded since the last rather
 * than [batch] of it: one claim a tick held the relay to [batch] events per `every`. It stops too when a full batch
 * publishes nothing, as when the bus refuses all of it, so a refusing bus is retried on the next tick, not spun on.
 */
private fun drained(outbox: Outbox, bus: EventBus, tracer: Tracer): List<ShopEvent> {
    val all = mutableListOf<ShopEvent>()
    while (true) {
        var claimed = 0
        val published = outbox.claim(batch) { rows -> published(rows, bus, tracer).also { claimed = rows.size } }
        all += published
        if (claimed < batch || published.isEmpty()) return all
    }
}

/** Each event is published inside the trace its write carried, so the bus's consumer continues that trace. */
private fun published(claimed: List<Claimed>, bus: EventBus, tracer: Tracer): List<ShopEvent> {
    gauge("petshop.outbox.claimed").set(claimed.size.toDouble())
    return claimed.mapNotNull { (event, carried) ->
        Carriers.within(carried) {
            tracer.span("publish", SpanKind.PRODUCER) {
                bus.publish(event).fold(
                    { refused ->
                        Span.current().setStatus(StatusCode.ERROR, refused.why)
                        logWarn("the bus refused event ${refused.seq}: ${refused.why}")
                        counter("petshop.outbox.refused").increment()
                        null
                    },
                    { published -> published },
                )
            }
        }
    }
}

val outbox: Module =
    single { source: DataSource, tracer: Tracer, _: Migrated -> PostgresOutbox(source, tracer) }.boundTo<Outbox>() +
        closingOf(
            { outbox: Outbox, config: Settings, bus: EventBus, streams: StreamBackend, tracer: Tracer ->
                relay(every = config.outboxEvery, outbox = outbox, bus = bus, tracer = tracer)
                    .start(streams)
                    .let(::OutboxRelay)
            },
        )
