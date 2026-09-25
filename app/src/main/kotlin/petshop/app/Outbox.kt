package petshop.app

import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.gauge
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.stream.Run
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.mapConcat
import io.github.matthewjones372.lark.stream.mapPar
import io.github.matthewjones372.lark.stream.restartOnDefect
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.stream.tick
import petshop.domain.ShopEvent
import kotlin.time.Duration

/**
 * Where the shop writes what happened, and where the relay reads it back from.
 *
 * An event leaves only once the bus has taken it, and a claim holds what it read from every other
 * claim until it has answered, so two relays never publish the same event at once.
 */
interface Outbox {

    /** Writes the event [numbered] makes, with the next `seq`, and answers it numbered. */
    fun record(numbered: (seq: Long) -> ShopEvent): ShopEvent

    /**
     * Up to [limit] of the events nobody has published, oldest first, held from every other claim while
     * [publish] runs. What [publish] answers with leaves the outbox; the rest stay for the next claim.
     * Answers what left.
     */
    fun claim(limit: Int, publish: (List<ShopEvent>) -> List<ShopEvent>): List<ShopEvent>
}

/** The relay's run, which answers with how many events it published once it is stopped. */
class OutboxRelay internal constructor(val running: Running<Nothing, Long>)

private const val batch = 100

/**
 * What the shop recorded, carried to the bus: at least once, in the order it was recorded unless the
 * bus turns one away, in which case that one goes again on a later tick.
 *
 * An event leaves the outbox only after the bus has taken it. The other order loses one whenever the
 * process stops in between, and this order publishes one twice, which a consumer that knows events
 * by their `seq` can shrug off.
 *
 * Every [every], the [outbox] is asked for a claim on what the shop has not sent, and each claimed
 * event is offered to [bus] while the claim is held. The description names no backend: the service
 * runs it on Pekko, and `RelaySpec` on a clock the test moves.
 */
internal fun relay(
    every: Duration,
    outbox: Outbox,
    bus: EventBus,
): Run<Nothing, Long> =
    Stream.tick(every = every, element = Unit)
        // The claim blocks on Postgres, and mapPar gives it a virtual thread rather than a Pekko one.
        // Publishing happens inside it, because the rows are only held while the claim is open.
        .mapPar(1) { _ -> outbox.claim(batch) { claimed -> published(claimed, bus) } }
        .mapConcat { published -> published }
        // A claim that throws — Postgres gone, a connection that timed out — is a defect, and without
        // this one would stop the outbox draining until the process restarted. Nothing is lost by
        // starting again: the claim rolled back, and whatever it held is in the outbox still.
        .restartOnDefect(Schedule.spaced(every))
        .runFold(0L) { published, event ->
            logAnnotated("seq" to event.seq.toString()) {
                logInfo("published ${event::class.simpleName}")
            }
            counter("petshop.outbox.published").increment()
            published + 1
        }

/**
 * A refusal is not the relay's failure: it is logged, counted, and left where it is for the next tick.
 * Only what the bus took is answered, and so only that leaves the outbox.
 */
private fun published(claimed: List<ShopEvent>, bus: EventBus): List<ShopEvent> {
    gauge("petshop.outbox.claimed").set(claimed.size.toDouble())
    return claimed.mapNotNull { event ->
        bus.publish(event).fold(
            { refused ->
                logWarn("the bus refused event ${refused.seq}: ${refused.why}")
                counter("petshop.outbox.refused").increment()
                null
            },
            { published -> published },
        )
    }
}

val outbox: Module =
    singleOf(::PostgresOutbox).boundTo<Outbox>() +
        singleOf(
            { outbox: Outbox, config: Settings, bus: EventBus, streams: StreamBackend ->
                relay(every = config.outboxEvery, outbox = outbox, bus = bus)
                    .start(streams)
                    .let(::OutboxRelay)
            },
            // Stopped before the bus it publishes to is closed, because it depends on the bus.
            { relay -> relay.running.close() },
        )
