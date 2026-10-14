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
    val metadata: String? = null,
)

@Serializable
@SerialName("pets")
data class PetRow(val id: Long, val name: String, val species: String, val adopted: Boolean)

/** ExoQuery has no locking clause of its own, so a free block adds one. */
@SqlFragment
fun <T> forUpdateSkipLocked(rows: SqlQuery<T>): SqlQuery<T> = sql {
    free("$rows FOR UPDATE SKIP LOCKED").asPure<SqlQuery<T>>()
}

/** If a claimer dies before its commit, the locks go with the connection and the rows are claimed again. */
class OutboxTable(dataSource: DataSource) {

    private val db: JdbcController = JdbcControllers.Postgres(dataSource)

    /** One transaction, so no event is recorded for a change the pets table does not hold. */
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

    fun stock(pets: List<PetRow>): Unit = runBlocking {
        db.transaction {
            pets.forEach { pet ->
                sql { insert<PetRow> { setParams(pet).onConflictIgnore(id) } }
                    .buildFor.Postgres()
                    .runOnTransaction()
            }
        }
    }

    fun pets(): List<PetRow> = runBlocking {
        sql { Table<PetRow>().sortedBy { it.id } }.buildFor.Postgres().runOn(db)
    }

    fun claim(limit: Int, handle: (List<OutboxRow>) -> List<Long>): List<Long> = runBlocking {
        db.transaction {
            val claimed = sql { forUpdateSkipLocked(Table<OutboxRow>().sortedBy { it.seq }.take(param(limit))) }
                .buildFor.Postgres()
                .runOnTransaction()
            // Called even when empty, so a caller counting claims counts empty ones too.
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
