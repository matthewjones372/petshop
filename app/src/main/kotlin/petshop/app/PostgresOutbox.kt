package petshop.app

import io.github.matthewjones372.lark.Carriers
import io.github.matthewjones372.lark.otel.span
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import kotlinx.serialization.json.Json
import petshop.domain.Adopted
import petshop.domain.Adopting
import petshop.domain.OnShelf
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.PetReturned
import petshop.domain.ShopEvent
import petshop.domain.Species
import petshop.domain.Standing
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

    override fun keep(pet: Pet) = table.keep(pet.row())

    override fun shelf(opening: List<Pet>): List<Pet> {
        table.stock(opening.map { it.row() })
        return table.pets().map { Pet(PetId(it.id), it.name, Species.valueOf(it.species), it.standing()) }
    }

    override fun claim(limit: Int, publish: (List<Claimed>) -> List<ShopEvent>): List<ShopEvent> {
        val taken = mutableListOf<ShopEvent>()
        table.claim(limit) { rows ->
            publish(rows.map { Claimed(it.event(), it.carried()) }).also { taken += it }.map { it.seq }
        }
        return taken
    }
}

private fun Pet.row(): PetRow = when (val now = standing) {
    OnShelf -> PetRow(id.value, name, species.name, "on_shelf", null)
    is Adopting -> PetRow(id.value, name, species.name, "adopting", now.by)
    is Adopted -> PetRow(id.value, name, species.name, "adopted", now.by)
}

private fun PetRow.standing(): Standing = when (standing) {
    "on_shelf" -> OnShelf
    "adopting" -> Adopting(checkNotNull(adoptedBy) { "pet $id is being adopted by nobody" })
    "adopted" -> Adopted(checkNotNull(adoptedBy) { "pet $id is adopted by nobody" })
    else -> error("pet $id stands $standing, which the shop never writes")
}

private fun ShopEvent.row(carried: Map<String, String>): OutboxRow {
    val metadata = carried.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }
    return when (this) {
        is PetArrived -> OutboxRow(
            seq,
            "arrived",
            pet.id.value,
            pet.name,
            pet.species.name,
            false,
            null,
            metadata,
        )

        is PetAdopted -> OutboxRow(seq, "adopted", pet.id.value, pet.name, pet.species.name, true, by, metadata)

        is PetReturned -> OutboxRow(seq, "returned", pet.id.value, pet.name, pet.species.name, false, null, metadata)
    }
}

private fun OutboxRow.carried(): Map<String, String> =
    metadata?.let { Json.decodeFromString<Map<String, String>>(it) } ?: emptyMap()

private fun OutboxRow.event(): ShopEvent {
    fun pet(standing: Standing) = Pet(PetId(petId), petName, Species.valueOf(species), standing)
    return when (kind) {
        "arrived" -> PetArrived(seq, pet(OnShelf))

        "adopted" -> checkNotNull(
            adoptedBy,
        ) { "adoption $seq names nobody" }.let { by -> PetAdopted(seq, pet(Adopted(by)), by) }

        else -> error("outbox row $seq is a $kind, which the shop never writes")
    }
}
