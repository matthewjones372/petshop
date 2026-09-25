package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.start
import petshop.api.SpeciesTally
import petshop.api.Tally
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetReturned
import petshop.domain.ShopEvent
import petshop.domain.Species
import java.util.concurrent.atomic.AtomicReference

/** The read side of the bus: the tally as of the last event the projection folded in, and the run doing it. */
class Projection internal constructor(
    private val latest: AtomicReference<Seen>,
    internal val running: Running<Nothing, Long>,
) {

    fun tally(): Tally = latest.get().tally
}

/** An event, and whether this consumer has been handed it before. */
private data class Delivery(val event: ShopEvent, val again: Boolean)

/** Every `seq` folded in so far, and what they came to. */
internal data class Seen(val seqs: Set<Long>, val tally: Tally) {

    operator fun plus(event: ShopEvent): Seen =
        Seen(seqs + event.seq, tally + Delivery(event, again = event.seq in seqs))
}

private val nothingYet = Tally(events = 0, duplicates = 0, bySpecies = Species.entries.map { SpeciesTally(it, 0, 0) })

/**
 * A consumer of the bus that owes the shop nothing: it reads what was published and folds it into a
 * read model, and a slow or broken one cannot hold up an adoption.
 *
 * The bus is at-least-once, so it knows events by `seq`. It keeps every `seq` it has seen rather than
 * the highest, because a retried event arrives after later ones and a high-water mark would drop it.
 * It runs on whichever backend the graph names, as the relay does, and is stopped before the bus closes.
 */
val projection: Module =
    singleOf(
        { bus: EventBus, streams: StreamBackend ->
            val latest = AtomicReference(Seen(emptySet(), nothingYet))
            Projection(latest, bus.consume { event -> latest.updateAndGet { it + event } }.start(streams))
        },
        { projection -> projection.running.close() },
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
