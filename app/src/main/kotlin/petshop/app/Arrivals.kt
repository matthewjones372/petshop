package petshop.app

import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.closingOf
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.gauge
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.otel.tracedSpan
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.stream.tick
import io.opentelemetry.api.trace.Tracer
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.Species
import java.util.concurrent.atomic.AtomicLong

class Arrivals internal constructor(val running: Running<Nothing, Long>) : AutoCloseable by running

private val names = listOf("Pickle", "Waffle", "Sprocket", "Marmalade", "Biscuit", "Clementine")

val arrivals: Module =
    closingOf(
        { ref: ActorRef<Shop>, shop: PetShop, config: Settings, streams: StreamBackend, tracer: Tracer ->
            // Start after the highest id held, so a pet arriving after a restart gets a new id.
            val next = AtomicLong(shop.all().maxOfOrNull { it.id.value } ?: opening.size.toLong())
            Stream.tick(every = config.arrivalsEvery, element = Unit)
                .map { _ ->
                    val id = next.incrementAndGet()
                    Pet(PetId(id), names[(id % names.size).toInt()], Species.entries[(id % 4).toInt()])
                }
                .runFold(0L) { arrived, pet ->
                    // Logged on the stream's thread, so pet_id is only on the line if bound here. Each arrival is
                    // a trace of its own, which its write, publish and processing continue.
                    logAnnotated("pet_id" to pet.id.value.toString()) {
                        tracer.tracedSpan("arrival") {
                            logInfo("${pet.name} the ${pet.species} arrived")
                            ref.tell(Arrived(pet))
                        }
                    }
                    counter("petshop.arrivals").increment()
                    // Ids are handed out in order, so the latest id is the count of pets ever arrived.
                    gauge("petshop.pets.in.shop").set(pet.id.value.toDouble())
                    arrived + 1
                }
                .start(streams)
                .let(::Arrivals)
        },
    )
