package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.pelican.health.Check
import io.github.matthewjones372.pelican.health.Health
import io.github.matthewjones372.pelican.health.Status
import io.github.matthewjones372.pelican.health.health
import io.github.matthewjones372.pelican.health.jdbc
import io.github.matthewjones372.pelican.health.noDeadlockedThreads
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds
import petshop.domain.PetShop

/**
 * What the shop needs to take traffic, each under the name an alert reports it by. `/health/ready` asks
 * every one, and [Meters] exports each as its own series, so the probe and the scrape cannot disagree on
 * what is checked.
 */
class Readiness(val checks: Map<String, Check>)

val checks: Module =
    single { pool: DataSource, shop: PetShop ->
        Readiness(
            mapOf(
                // A connection the pool can hand out and Postgres says is good.
                "database" to Check { jdbc(pool, timeout = 2.seconds) },
                // The actor answering at all: an ask that times out throws, and Pelican counts a throw as a fail.
                "shop" to Check { if (shop.all().isNotEmpty()) Status.Pass else Status.Fail("the shelf is empty") },
            ),
        )
    } +
        single { readiness: Readiness ->
            health {
                live("threads") { noDeadlockedThreads() }
                readiness.checks.forEach { (name, check) -> ready(name, timeout = 3.seconds, check = check) }
            }
        }
