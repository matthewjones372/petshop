package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * One Postgres for the whole test run, started the first time a test asks for it. Testcontainers'
 * reaper stops it once the JVM has gone.
 */
object TestPostgres {

    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:17-alpine").apply { start() }
    }

    private val schemas = AtomicInteger()

    /**
     * An empty schema of its own, so no test reads an event another test left in its outbox. The
     * shop creates the table in it, exactly as it does against the real one.
     */
    fun fresh(): DatabaseSettings {
        val schema = "test_${schemas.incrementAndGet()}_${ProcessHandle.current().pid()}"
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        val url = container.jdbcUrl + (if ('?' in container.jdbcUrl) "&" else "?") + "currentSchema=$schema"
        return DatabaseSettings(url, container.username, container.password)
    }
}

/** This graph with its outbox in an empty schema of the test Postgres, rather than application.conf's. */
fun Module.onAFreshDatabase(): Module = overriding(single<DatabaseSettings> { TestPostgres.fresh() })

/**
 * This graph with its outbox in [database], rather than application.conf's. Two graphs on the same
 * settings are two instances of the service writing one table, and running one relay each.
 */
fun Module.onDatabase(database: DatabaseSettings): Module = overriding(single<DatabaseSettings> { database })

/** How many events have ever been written to the outbox: the last `seq` Postgres handed out. */
fun DatabaseSettings.recorded(): Long =
    asking("SELECT coalesce(pg_sequence_last_value(pg_get_serial_sequence('outbox', 'seq')::regclass), 0)")

/** How many events are in the outbox still, waiting for a relay. */
fun DatabaseSettings.unsent(): Long = asking("SELECT count(*) FROM outbox")

private fun DatabaseSettings.asking(query: String): Long =
    DriverManager.getConnection(url, user, password).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { rows -> rows.next(); rows.getLong(1) }
        }
    }
