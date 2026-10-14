package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.closingOf
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.start
import java.util.concurrent.atomic.AtomicReference
import petshop.api.SpeciesTally
import petshop.api.Tally
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetReturned
import petshop.domain.ShopEvent
import petshop.domain.Species

class Projection internal constructor(
    private val latest: AtomicReference<Seen>,
    internal val running: Running<Nothing, Long>,
) : AutoCloseable by running {

    fun tally(): Tally = latest.get().tally
}

private data class Delivery(val event: ShopEvent, val again: Boolean)

internal data class Seen(val seqs: Set<Long>, val tally: Tally) {

    operator fun plus(event: ShopEvent): Seen =
        Seen(seqs + event.seq, tally + Delivery(event, again = event.seq in seqs))
}

private val nothingYet = Tally(events = 0, duplicates = 0, bySpecies = Species.entries.map { SpeciesTally(it, 0, 0) })

/**
 * The bus is at-least-once, so every `seq` seen is kept rather than the highest: a retried event can
 * arrive after later ones, and a high-water mark would drop it.
 */
val projection: Module =
    closingOf(
        { bus: EventBus, streams: StreamBackend, feed: Feed ->
            val latest = AtomicReference(Seen(emptySet(), nothingYet))
            // One consumer, so nothing changes `latest` between the check and the update.
            val consuming = bus.consume { event ->
                val first = event.seq !in latest.get().seqs
                latest.updateAndGet { it + event }
                if (first) feed.offer(event)
            }
            Projection(latest, consuming.start(streams))
        },
    )

private operator fun Tally.plus(delivery: Delivery): Tally {
    if (delivery.again) {
        counter("petshop.projection.duplicates").increment()
        return copy(duplicates = duplicates + 1)
    }
    return when (val event = delivery.event) {
        is PetArrived -> counted(event.pet.species) { it.copy(arrived = it.arrived + 1) }
        is PetAdopted -> counted(event.pet.species) { it.copy(adopted = it.adopted + 1) }
        is PetReturned -> counted(event.pet.species) { it.copy(adopted = it.adopted - 1) }
    }
}

private fun Tally.counted(species: Species, change: (SpeciesTally) -> SpeciesTally): Tally =
    copy(events = events + 1, bySpecies = bySpecies.map { if (it.species == species) change(it) else it })
