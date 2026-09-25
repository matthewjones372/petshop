package petshop.app

import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.pekko.ask
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
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import petshop.domain.ShopEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 * Every [every], [unsent] is asked for what the shop has not sent, each is offered to [bus], and [sent]
 * is told of each one the bus took. The description names no backend: the service runs it on Pekko,
 * and `RelaySpec` on a clock the test moves.
 */
internal fun relay(
    every: Duration,
    unsent: () -> List<ShopEvent>,
    bus: EventBus,
    sent: (ShopEvent) -> Unit,
): Run<Nothing, Long> =
    Stream.tick(every = every, element = Unit)
        // The ask blocks, and mapPar gives it a virtual thread rather than a Pekko one.
        .mapPar(1) { _ -> unsent() }
        .mapConcat { unsent ->
            gauge("petshop.outbox.unsent").set(unsent.size.toDouble())
            unsent
        }
        // A refusal is not the relay's failure: it is logged, counted, and left where it is for the
        // next tick. Only what the bus took carries on to be marked sent.
        .mapConcat { event ->
            bus.publish(event).fold(
                { refused ->
                    logWarn("the bus refused event ${refused.seq}: ${refused.why}")
                    counter("petshop.outbox.refused").increment()
                    emptyList()
                },
                { published -> listOf(published) },
            )
        }
        // A timed-out ask is a defect, and without this one would stop the outbox draining until the
        // process restarted. Nothing is lost by starting again: whatever was not marked sent is still
        // in the outbox.
        .restartOnDefect(Schedule.spaced(every))
        .runFold(0L) { published, event ->
            logAnnotated("seq" to event.seq.toString()) {
                logInfo("published ${event::class.simpleName}")
            }
            counter("petshop.outbox.published").increment()
            sent(event)
            published + 1
        }

val outbox: Module =
    singleOf(
        { ref: ActorRef<Shop>, config: Settings, system: ActorSystem, bus: EventBus, streams: StreamBackend ->
            relay(
                every = config.outboxEvery,
                unsent = {
                    ref.ask(system, 3.seconds) { replyTo: ActorRef<List<ShopEvent>> -> Unsent(batch, replyTo) }
                },
                bus = bus,
                sent = { published -> ref.tell(Sent(published.seq)) },
            )
                .start(streams)
                .let(::OutboxRelay)
        },
        // Stopped before the bus it publishes to is closed, because it depends on the bus.
        { relay -> relay.running.close() },
    )
