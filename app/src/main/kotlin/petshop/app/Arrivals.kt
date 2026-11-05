package petshop.app

import dev.surefold.actor.ActorRef
import dev.surefold.app.Module
import dev.surefold.app.closingOf
import dev.surefold.counter
import dev.surefold.gauge
import dev.surefold.increment
import dev.surefold.logAnnotated
import dev.surefold.logInfo
import dev.surefold.otel.tracedSpan
import dev.surefold.stream.Running
import dev.surefold.stream.Stream
import dev.surefold.stream.StreamBackend
import dev.surefold.stream.map
import dev.surefold.stream.runFold
import dev.surefold.stream.start
import dev.surefold.stream.tick
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
