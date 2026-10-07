package petshop.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.probe
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.typesafe.config
import io.micrometer.core.instrument.Metrics
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

/** Where the shop's Postgres is. */
data class DatabaseSettings(val url: String, val user: String, val password: String)

/**
 * The shop's two tables. `pets` is the catalogue as the shop last left it, and `outbox` the events that changed
 * it, written together in one transaction. `seq` is Postgres's to hand out, so it keeps counting across restarts
 * and across every instance writing to the same table, which a counter in the actor could not.
 */
private val schema = """
    CREATE TABLE IF NOT EXISTS pets (
        id      BIGINT  PRIMARY KEY,
        name    TEXT    NOT NULL,
        species TEXT    NOT NULL,
        adopted BOOLEAN NOT NULL
    );
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
            // Two seconds, not Hikari's thirty: with Postgres gone an adoption is refused and the health probe
            // fails while someone is still watching, instead of half a minute later.
            connectionTimeout = 2_000
            // The pool's own meters (hikaricp_connections_active, _pending, _timeout_total), on Micrometer's
            // global composite, where /metrics reads them.
            metricRegistry = Metrics.globalRegistry
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
            // A connection the pool can hand out and Postgres says is good, or the shop is not ready. A pool with no
            // connection to give throws, which Lark counts as the probe failing (Lark spec 0126).
            .probe("database", timeout = 3.seconds) { pool: DataSource ->
                pool.connection.use { it.isValid(PROBE_SECONDS) }
            }

private const val PROBE_SECONDS = 2
