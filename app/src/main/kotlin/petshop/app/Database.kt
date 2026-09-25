package petshop.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.typesafe.config
import javax.sql.DataSource

/** Where the shop's Postgres is. */
data class DatabaseSettings(val url: String, val user: String, val password: String)

/**
 * The one table the shop keeps. `seq` is Postgres's to hand out, so it keeps counting across restarts
 * and across every instance writing to the same table, which a counter in the actor could not.
 */
private val schema = """
    CREATE TABLE IF NOT EXISTS outbox (
        seq        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        kind       TEXT    NOT NULL CHECK (kind IN ('arrived', 'adopted', 'returned')),
        pet_id     BIGINT  NOT NULL,
        pet_name   TEXT    NOT NULL,
        species    TEXT    NOT NULL,
        adopted    BOOLEAN NOT NULL,
        adopted_by TEXT
    )
""".trimIndent()

/** A pool with the table in it: nothing that takes the pool has to ask whether the table exists yet. */
private fun pool(settings: DatabaseSettings): HikariDataSource {
    val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = settings.url
            username = settings.user
            password = settings.password
            poolName = "petshop"
        },
    )
    runCatching { pool.connection.use { connection -> connection.createStatement().use { it.execute(schema) } } }
        .onFailure { pool.close() }
        .getOrThrow()
    return pool
}

val database: Module =
    config<DatabaseSettings>("petshop.database") {
        DatabaseSettings(string("url"), string("user"), string("password"))
    } +
        // Released after the actor and the relay, which both depend on it: the pool closes once
        // nothing is left to borrow from it.
        singleOf({ settings: DatabaseSettings -> pool(settings) }, { pool -> pool.close() })
            .boundTo<DataSource>()
