package petshop.app

import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.PetReturned
import petshop.domain.ShopEvent
import petshop.domain.Species
import petshop.outbox.OutboxRow
import petshop.outbox.PetRow
import petshop.outbox.OutboxTable
import javax.sql.DataSource

/**
 * Claims use `FOR UPDATE SKIP LOCKED`, so concurrent relays never block each other or publish the same
 * row at once. A crash between publish and commit publishes the rows again: delivery is at-least-once.
 */
class PostgresOutbox(dataSource: DataSource) : Outbox {

    private val table = OutboxTable(dataSource)

    // Postgres assigns the seq, so the row is built with a placeholder of 0.
    override fun record(pet: Pet, numbered: (seq: Long) -> ShopEvent): ShopEvent =
        numbered(table.record(pet.row(), numbered(0).row()))

    override fun shelf(opening: List<Pet>): List<Pet> {
        table.stock(opening.map { it.row() })
        return table.pets().map { Pet(PetId(it.id), it.name, Species.valueOf(it.species), it.adopted) }
    }

    override fun claim(limit: Int, publish: (List<ShopEvent>) -> List<ShopEvent>): List<ShopEvent> {
        val taken = mutableListOf<ShopEvent>()
        table.claim(limit) { rows -> publish(rows.map { it.event() }).also { taken += it }.map { it.seq } }
        return taken
    }
}

private fun Pet.row(): PetRow = PetRow(id.value, name, species.name, adopted)

private fun ShopEvent.row(): OutboxRow = when (this) {
    is PetArrived -> OutboxRow(seq, "arrived", pet.id.value, pet.name, pet.species.name, pet.adopted, null)
    is PetAdopted -> OutboxRow(seq, "adopted", pet.id.value, pet.name, pet.species.name, pet.adopted, by)
    is PetReturned -> OutboxRow(seq, "returned", pet.id.value, pet.name, pet.species.name, pet.adopted, null)
}

private fun OutboxRow.event(): ShopEvent {
    val pet = Pet(PetId(petId), petName, Species.valueOf(species), adopted)
    return when (kind) {
        "arrived" -> PetArrived(seq, pet)
        "adopted" -> PetAdopted(seq, pet, checkNotNull(adoptedBy) { "adoption $seq names nobody" })
        "returned" -> PetReturned(seq, pet)
        else -> error("outbox row $seq is a $kind, which the shop never writes")
    }
}
