package petshop.app

import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.PetReturned
import petshop.domain.ShopEvent
import petshop.domain.Species
import petshop.outbox.OutboxRow
import petshop.outbox.OutboxTable
import javax.sql.DataSource

/**
 * The outbox as a Postgres table. The SQL is [OutboxTable]'s, in ExoQuery; what is left here is the
 * shop's side of it, which is an event flattened into a row and read back out of one.
 *
 * A claim selects the oldest unpublished rows `FOR UPDATE SKIP LOCKED`, publishes them, and deletes the
 * ones the bus took before it commits. Two relays claiming at once — two instances of the service, or
 * a restarted relay beside one still finishing — never wait for each other and never hand the bus the
 * same row at the same time. If the process dies between publishing and committing, the rows are
 * claimed again, which is the at-least-once the consumer already expects.
 */
class PostgresOutbox(dataSource: DataSource) : Outbox {

    private val table = OutboxTable(dataSource)

    // Everything in a row but its seq is known before Postgres numbers it.
    override fun record(numbered: (seq: Long) -> ShopEvent): ShopEvent = numbered(table.insert(numbered(0).row()))

    override fun claim(limit: Int, publish: (List<ShopEvent>) -> List<ShopEvent>): List<ShopEvent> {
        val taken = mutableListOf<ShopEvent>()
        table.claim(limit) { rows -> publish(rows.map { it.event() }).also { taken += it }.map { it.seq } }
        return taken
    }
}

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
