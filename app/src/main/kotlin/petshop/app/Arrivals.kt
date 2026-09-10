package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.runWith
import io.github.matthewjones372.lark.stream.tick
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.stream.javadsl.Sink
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.Species
import java.util.concurrent.atomic.AtomicLong

/** The background work every service has one of: new pets keep turning up. */
class Arrivals internal constructor()

private val names = listOf("Pickle", "Waffle", "Sprocket", "Marmalade", "Biscuit", "Clementine")

/**
 * A stream rather than a scheduled callback, because the failure a feed can end with belongs in the
 * type: `Stream<Nothing, Pet>` says this one cannot fail, and `run` answers an `Exit` rather than a
 * future nobody read.
 */
val arrivals: Module =
    single { ref: ActorRef<Shop>, config: Settings, system: ActorSystem ->
        val next = AtomicLong(opening.size.toLong())
        Stream.tick(every = config.arrivalsEvery, element = Unit)
            .map { _ ->
                val id = next.incrementAndGet()
                Pet(PetId(id), names[(id % names.size).toInt()], Species.entries[(id % 4).toInt()])
            }
            .runWith(Sink.foreach { pet -> ref.tell(Arrived(pet)) })
            .run(system)
        Arrivals()
    }
