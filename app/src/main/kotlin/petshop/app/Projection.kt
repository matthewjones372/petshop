package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.lark.stream.scan
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.stream.statefulMap
import petshop.api.SpeciesTally
import petshop.api.Tally
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetReturned
import petshop.domain.ShopEvent
import petshop.domain.Species
import java.util.concurrent.atomic.AtomicReference

/** The read side of the bus: the tally as of the last event the projection folded in. */
class Projection internal constructor(
    private val latest: AtomicReference<Tally>,
    internal val running: Running<Nothing, Tally>,
) {

    fun tally(): Tally = latest.get()
}

/** An event, and whether this consumer has been handed it before. */
private data class Delivery(val event: ShopEvent, val again: Boolean)

private val nothingYet = Tally(events = 0, duplicates = 0, bySpecies = Species.entries.map { SpeciesTally(it, 0, 0) })

/**
 * A consumer of the bus that owes the shop nothing: it reads what was published and folds it into a
 * read model, and a slow or broken one cannot hold up an adoption.
 *
 * The bus is at-least-once, so it knows events by `seq`. It keeps every `seq` it has seen rather than
 * the highest, because a retried event arrives after later ones and a high-water mark would drop it.
 */
val projection: Module =
    singleOf(
        { bus: EventBus, streams: StreamBackend ->
            val latest = AtomicReference(nothingYet)
            val running = bus.subscribe()
                .statefulMap(
                    create = { emptySet<Long>() },
                    f = { seen, event -> (seen + event.seq) to Delivery(event, again = event.seq in seen) },
                )
                .scan(nothingYet) { tally, delivery -> tally + delivery }
                .runFold(nothingYet) { _, tally -> tally.also(latest::set) }
                .start(streams)
            Projection(latest, running)
        },
        // Stopped before the bus it reads is closed, because it depends on the bus.
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
