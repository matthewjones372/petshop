package petshop.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.typesafe.Secret
import io.github.matthewjones372.lark.app.typesafe.config
import io.micrometer.core.instrument.Metrics
import javax.sql.DataSource

data class DatabaseSettings(val url: String, val user: String, val password: Secret)

/** `seq` comes from Postgres so it keeps counting across restarts and instances, which an actor counter could not. */
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

private fun pool(settings: DatabaseSettings): HikariDataSource {
    val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = settings.url
            username = settings.user
            password = settings.password.reveal()
            poolName = "petshop"
            // Two seconds, not Hikari's thirty, so a down Postgres fails adoptions and readiness promptly.
            connectionTimeout = 2_000
            // The global composite, where /metrics reads the pool's hikaricp_* meters.
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
        DatabaseSettings(string("url"), string("user"), secret("password"))
    } +
        singleOf({ settings: DatabaseSettings -> pool(settings) }, { pool -> pool.close() })
            .boundTo<DataSource>()
