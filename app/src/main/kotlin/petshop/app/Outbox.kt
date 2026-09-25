package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.pekko.ask
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.gauge
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.divertLefts
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.mapConcat
import io.github.matthewjones372.lark.stream.mapPar
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.runWith
import io.github.matthewjones372.lark.stream.takeWhile
import io.github.matthewjones372.lark.stream.tick
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.stream.javadsl.Sink
import petshop.domain.ShopEvent
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * The relay's run, and the way to stop it: it finishes the batch in hand and ends at the next tick,
 * so the bus it publishes to is not closed underneath it.
 */
class OutboxRelay internal constructor(
    private val open: AtomicBoolean,
    private val stopped: CompletionStage<Exit<Nothing, Done>>,
) : AutoCloseable {

    override fun close() {
        open.set(false)
        stopped.toCompletableFuture().get(stopWithin, TimeUnit.SECONDS)
    }
}

private const val stopWithin = 10L

private const val batch = 100

/**
 * What the shop recorded, carried to the bus: at least once, in the order it was recorded unless the
 * bus turns one away, in which case that one goes again on a later tick.
 *
 * An event leaves the outbox only after the bus has taken it. The other order loses one whenever the
 * process stops in between, and this order publishes one twice, which a consumer that knows events
 * by their `seq` can shrug off.
 */
val outbox: Module =
    singleOf(
        { ref: ActorRef<Shop>, config: Settings, system: ActorSystem, bus: EventBus ->
            val open = AtomicBoolean(true)
            val stopped =
                Stream.tick(every = config.outboxEvery, element = Unit)
                    .takeWhile { open.get() }
                    // The ask blocks, and mapPar gives it a virtual thread rather than a Pekko one.
                    // `Nothing` is spelled out: on a stream that has not named a failure, a body
                    // that never raises leaves mapPar nothing to infer one from.
                    .mapPar<Nothing, _, _>(1) { _ ->
                        ref.ask(system, 3.seconds) { replyTo: ActorRef<List<ShopEvent>> -> Unsent(batch, replyTo) }
                    }
                    .mapConcat { unsent ->
                        gauge("petshop.outbox.unsent").set(unsent.size.toDouble())
                        unsent
                    }
                    .map { event -> bus.publish(event) }
                    // A refusal is not the relay's failure: it is logged, counted, and left where
                    // it is for the next tick. Only what the bus took carries on to be marked sent.
                    .divertLefts(
                        to = Sink.foreach { refused: BusRefused ->
                            logWarn("the bus refused event ${refused.seq}: ${refused.why}")
                            counter("petshop.outbox.refused").increment()
                        },
                    )
                    .runWith(
                        Sink.foreach { published: ShopEvent ->
                            logAnnotated("seq" to published.seq.toString()) {
                                logInfo("published ${published::class.simpleName}")
                            }
                            counter("petshop.outbox.published").increment()
                            ref.tell(Sent(published.seq))
                        },
                    )
                    .run(system)
            OutboxRelay(open, stopped)
        },
        { relay -> relay.close() },
    )
