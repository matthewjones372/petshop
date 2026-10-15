package petshop.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.closingOf
import io.github.matthewjones372.lark.app.liquibase.migrations
import io.github.matthewjones372.lark.app.typesafe.Secret
import io.github.matthewjones372.lark.app.typesafe.config
import io.micrometer.core.instrument.Metrics
import javax.sql.DataSource

data class DatabaseSettings(val url: String, val user: String, val password: Secret)

private fun pool(settings: DatabaseSettings): HikariDataSource =
    HikariDataSource(
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

val database: Module =
    config<DatabaseSettings>("petshop.database") {
        DatabaseSettings(string("url"), string("user"), secret("password"))
    } +
        closingOf(::pool)
            .boundTo<DataSource>() +
        // Anything that reads the tables takes a Migrated, so it cannot be built before they exist.
        migrations("db/changelog.sql")
