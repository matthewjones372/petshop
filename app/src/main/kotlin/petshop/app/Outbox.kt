package petshop.app

import io.github.matthewjones372.surefold.Carriers
import io.github.matthewjones372.surefold.Schedule
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.boundTo
import io.github.matthewjones372.surefold.app.closingOf
import io.github.matthewjones372.surefold.app.liquibase.Migrated
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.counter
import io.github.matthewjones372.surefold.fail.fold
import io.github.matthewjones372.surefold.gauge
import io.github.matthewjones372.surefold.increment
import io.github.matthewjones372.surefold.logAnnotated
import io.github.matthewjones372.surefold.logInfo
import io.github.matthewjones372.surefold.logWarn
import io.github.matthewjones372.surefold.stream.Run
import io.github.matthewjones372.surefold.stream.Running
import io.github.matthewjones372.surefold.stream.Stream
import io.github.matthewjones372.surefold.stream.StreamBackend
import io.github.matthewjones372.surefold.stream.mapConcat
import io.github.matthewjones372.surefold.stream.mapPar
import io.github.matthewjones372.surefold.stream.restartOnDefect
import io.github.matthewjones372.surefold.stream.runFold
import io.github.matthewjones372.surefold.stream.start
import io.github.matthewjones372.surefold.stream.tick
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
    .mapPar(1) { _ -> outbox.claim(batch) { claimed -> published(claimed, bus, tracer) } }
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
