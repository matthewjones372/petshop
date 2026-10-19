package petshop.app

import io.github.matthewjones372.lark.Carriers
import io.github.matthewjones372.lark.otel.span
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import kotlinx.serialization.json.Json
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.PetReturned
import petshop.domain.ShopEvent
import petshop.domain.Species
import petshop.outbox.OutboxRow
import petshop.outbox.OutboxTable
import petshop.outbox.PetRow
import javax.sql.DataSource

/**
 * Claims use `FOR UPDATE SKIP LOCKED`, so concurrent relays never block each other or publish the same
 * row at once. A crash between publish and commit publishes the rows again: delivery is at-least-once.
 */
class PostgresOutbox(dataSource: DataSource, private val tracer: Tracer) : Outbox {

    private val table = OutboxTable(dataSource)

    // Postgres assigns the seq, so the row is built with a placeholder of 0. Captured inside the span, so the
    // relay's publish is this write's child.
    override fun record(pet: Pet, numbered: (seq: Long) -> ShopEvent): ShopEvent = tracer.span("INSERT outbox") {
        Span.current().setAttribute("db.system.name", "postgresql")
        numbered(table.record(pet.row(), numbered(0).row(Carriers.capture())))
    }

    override fun shelf(opening: List<Pet>): List<Pet> {
        table.stock(opening.map { it.row() })
        return table.pets().map { Pet(PetId(it.id), it.name, Species.valueOf(it.species), it.adopted) }
    }

    override fun claim(limit: Int, publish: (List<Claimed>) -> List<ShopEvent>): List<ShopEvent> {
        val taken = mutableListOf<ShopEvent>()
        table.claim(limit) { rows ->
            publish(rows.map { Claimed(it.event(), it.carried()) }).also { taken += it }.map { it.seq }
        }
        return taken
    }
}

private fun Pet.row(): PetRow = PetRow(id.value, name, species.name, adopted)

private fun ShopEvent.row(carried: Map<String, String>): OutboxRow {
    val metadata = carried.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }
    return when (this) {
        is PetArrived -> OutboxRow(
            seq,
            "arrived",
            pet.id.value,
            pet.name,
            pet.species.name,
            pet.adopted,
            null,
            metadata,
        )

        is PetAdopted -> OutboxRow(seq, "adopted", pet.id.value, pet.name, pet.species.name, pet.adopted, by, metadata)

        is PetReturned -> OutboxRow(
            seq,
            "returned",
            pet.id.value,
            pet.name,
            pet.species.name,
            pet.adopted,
            null,
            metadata,
        )
    }
}

private fun OutboxRow.carried(): Map<String, String> =
    metadata?.let { Json.decodeFromString<Map<String, String>>(it) } ?: emptyMap()

private fun OutboxRow.event(): ShopEvent {
    val pet = Pet(PetId(petId), petName, Species.valueOf(species), adopted)
    return when (kind) {
        "arrived" -> PetArrived(seq, pet)
        "adopted" -> PetAdopted(seq, pet, checkNotNull(adoptedBy) { "adoption $seq names nobody" })
        "returned" -> PetReturned(seq, pet)
        else -> error("outbox row $seq is a $kind, which the shop never writes")
    }
}
