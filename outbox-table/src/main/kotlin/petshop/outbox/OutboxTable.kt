package petshop.outbox

import io.exoquery.SqlFragment
import io.exoquery.SqlQuery
import io.exoquery.controller.jdbc.JdbcController
import io.exoquery.controller.transaction
import io.exoquery.controller.jdbc.JdbcControllers
import io.exoquery.jdbc.runOn
import io.exoquery.sql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.sql.DataSource

/** A row of `outbox`: one event, flattened. [adoptedBy] is there only for an adoption. */
@Serializable
@SerialName("outbox")
data class OutboxRow(
    val seq: Long,
    val kind: String,
    @SerialName("pet_id") val petId: Long,
    @SerialName("pet_name") val petName: String,
    val species: String,
    val adopted: Boolean,
    @SerialName("adopted_by") val adoptedBy: String?,
)

/** A row of `pets`: one pet as the shop last left it. */
@Serializable
@SerialName("pets")
data class PetRow(val id: Long, val name: String, val species: String, val adopted: Boolean)

/**
 * Locks the rows [rows] selects, and passes over any another transaction has already locked rather
 * than waiting for it. ExoQuery has no locking clause of its own, so a free block adds one.
 */
@SqlFragment
fun <T> forUpdateSkipLocked(rows: SqlQuery<T>): SqlQuery<T> = sql {
    free("$rows FOR UPDATE SKIP LOCKED").asPure<SqlQuery<T>>()
}

/**
 * `outbox` and `pets`, every statement against them written in ExoQuery. It knows rows and nothing of what the
 * shop puts in them.
 *
 * A claim is one transaction: the oldest rows are selected `FOR UPDATE SKIP LOCKED`, handed over, and
 * the ones handed back are deleted before it commits. Two claims at once never wait for each other and
 * never see the same row: each takes the rows the other has not locked. If the claimer dies before the
 * commit, the locks go with the connection and the rows are claimed again.
 */
class OutboxTable(dataSource: DataSource) {

    private val db: JdbcController = JdbcControllers.Postgres(dataSource)

    /**
     * Writes [pet] as it now is and inserts [event], but for its `seq`, in one transaction: both rows are written or
     * neither is, so no event is recorded for a change the pets table does not hold. Answers the event's `seq`.
     */
    fun record(pet: PetRow, event: OutboxRow): Long = runBlocking {
        db.transaction {
            sql {
                insert<PetRow> {
                    setParams(pet).onConflictUpdate(id) { excluded ->
                        set(name to excluded.name, species to excluded.species, adopted to excluded.adopted)
                    }
                }
            }
                .buildFor.Postgres()
                .runOnTransaction()
            sql { insert<OutboxRow> { setParams(event).excluding(seq) }.returning { it.seq } }
                .buildFor.Postgres()
                .runOnTransaction()
        }
    }

    /** Adds each of [pets] not there yet; a pet already there keeps what it has. */
    fun stock(pets: List<PetRow>): Unit = runBlocking {
        db.transaction {
            pets.forEach { pet ->
                sql { insert<PetRow> { setParams(pet).onConflictIgnore(id) } }
                    .buildFor.Postgres()
                    .runOnTransaction()
            }
        }
    }

    /** Every pet as the shop last left it, by id. */
    fun pets(): List<PetRow> = runBlocking {
        sql { Table<PetRow>().sortedBy { it.id } }.buildFor.Postgres().runOn(db)
    }

    /**
     * Up to [limit] rows, oldest first — none, if there are none — locked while [handle] runs. The rows whose `seq` [handle]
     * answers with are deleted; the rest are let go. Answers the deleted `seq`s.
     */
    fun claim(limit: Int, handle: (List<OutboxRow>) -> List<Long>): List<Long> = runBlocking {
        db.transaction {
            val claimed = sql { forUpdateSkipLocked(Table<OutboxRow>().sortedBy { it.seq }.take(param(limit))) }
                .buildFor.Postgres()
                .runOnTransaction()
            // Handed over even when empty, so whoever counts a claim counts an empty one too.
            val done = handle(claimed)
            if (done.isNotEmpty()) {
                sql { delete<OutboxRow>().where { seq in params(done) } }
                    .buildFor.Postgres()
                    .runOnTransaction()
            }
            done
        }
    }
}
