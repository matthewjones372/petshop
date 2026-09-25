package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.gauge
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.runWith
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.stream.tick
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.stream.javadsl.Sink
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.Species
import java.util.concurrent.atomic.AtomicLong

/** The background work every service has one of: new pets keep turning up. */
class Arrivals internal constructor(val running: Running<Nothing, Done>)

private val names = listOf("Pickle", "Waffle", "Sprocket", "Marmalade", "Biscuit", "Clementine")

/**
 * A stream rather than a scheduled callback, because the failure a feed can end with belongs in the
 * type: `Stream<Nothing, Pet>` says this one cannot fail, and `start` answers an `Exit` rather than a
 * future nobody read, beside the way to stop it when the graph is given back.
 */
val arrivals: Module =
    singleOf(
        { ref: ActorRef<Shop>, config: Settings, system: ActorSystem ->
            val next = AtomicLong(opening.size.toLong())
            Stream.tick(every = config.arrivalsEvery, element = Unit)
                .map { _ ->
                    val id = next.incrementAndGet()
                    Pet(PetId(id), names[(id % names.size).toInt()], Species.entries[(id % 4).toInt()])
                }
                .runWith(
                    Sink.foreach { pet ->
                        // Written on a Pekko thread, not a request's: the pair is on the line because
                        // it is bound here, which is the only way this one could carry it.
                        logAnnotated("pet_id" to pet.id.value.toString()) {
                            logInfo("${pet.name} the ${pet.species} arrived")
                        }
                        counter("petshop.arrivals").increment()
                        // The shop's size as a number rather than a rate: the id is the count, since
                        // every pet that has ever arrived got the next one.
                        gauge("petshop.pets.in.shop").set(pet.id.value.toDouble())
                        ref.tell(Arrived(pet))
                    },
                )
                .start(system)
                .let(::Arrivals)
        },
        { arrivals -> arrivals.running.close() },
    )
