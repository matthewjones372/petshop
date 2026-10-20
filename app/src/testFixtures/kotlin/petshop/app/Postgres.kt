package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.typesafe.Secret
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import org.testcontainers.postgresql.PostgreSQLContainer

object TestPostgres {

    // With TESTCONTAINERS_REUSE_ENABLE=true every JVM shares one container. Pitest starts a JVM per batch of
    // mutants, and each fresh schema keeps tests apart, so only the first JVM pays for the start.
    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:17-alpine").withReuse(true).apply { start() }
    }

    private val schemas = AtomicInteger()

    /** An empty schema of its own, so no test reads an event another test left in its outbox. */
    fun fresh(): DatabaseSettings {
        val schema = "test_${schemas.incrementAndGet()}_${ProcessHandle.current().pid()}"
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        val url = container.jdbcUrl + (if ('?' in container.jdbcUrl) "&" else "?") + "currentSchema=$schema"
        return DatabaseSettings(url, container.username, Secret(container.password))
    }
}

fun Module.onAFreshDatabase(): Module = overriding(single<DatabaseSettings> { TestPostgres.fresh() })

/** Two graphs on the same [database] are two instances writing one table, each running a relay. */
fun Module.onDatabase(database: DatabaseSettings): Module = overriding(single<DatabaseSettings> { database })

fun DatabaseSettings.recorded(): Long =
    asking("SELECT coalesce(pg_sequence_last_value(pg_get_serial_sequence('outbox', 'seq')::regclass), 0)")

fun DatabaseSettings.unsent(): Long = asking("SELECT count(*) FROM outbox")

private fun DatabaseSettings.asking(query: String): Long =
    DriverManager.getConnection(url, user, password.reveal()).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { rows -> rows.next(); rows.getLong(1) }
        }
    }
