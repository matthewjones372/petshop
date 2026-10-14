package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.stream.Running
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
 * What the shop is checked on, each under the name an alert reports it by. `/health/ready` asks every one, and
 * [Meters] exports each as its own series, so the probe and the scrape cannot disagree on what is checked.
 *
 * [critical] is what the shop needs to take traffic: one failing answers 503. [background] is the work that runs
 * beside the requests: one stopped is a warning, still a 200, because a stale `/stats` is no reason to drain an
 * instance that can still sell a pet.
 */
class Readiness(val critical: Map<String, Check>, val background: Map<String, Check> = emptyMap()) {

    val checks: Map<String, Check> get() = critical + background

    /** The checks failing now. A throw is a failure and a warning is not, as `/health/ready` counts them. */
    fun failing(): Set<String> = checks.filterValues { check -> !passes(check) }.keys

    @Suppress("TooGenericExceptionCaught")
    private fun passes(check: Check): Boolean =
        try {
            check.check() !is Status.Fail
        } catch (_: Exception) {
            false
        }
}

/** Passes while [running] has not ended: a stream that ended on its own is work the shop has stopped doing. */
private fun still(running: Running<*, *>): Check = Check {
    val exit = running.exit.toCompletableFuture()
    if (exit.isDone) Status.Fail("ended: ${exit.join()}") else Status.Pass
}

val checks: Module =
    single { pool: DataSource, shop: PetShop, relay: OutboxRelay, projection: Projection, arrivals: Arrivals ->
        Readiness(
            critical = mapOf(
                // A connection the pool can hand out and Postgres says is good.
                "database" to Check { jdbc(pool, timeout = 2.seconds) },
                // The actor answering at all: an ask that times out throws, and Pelican counts a throw as a fail.
                "shop" to Check { if (shop.all().isNotEmpty()) Status.Pass else Status.Fail("the shelf is empty") },
            ),
            background = mapOf(
                "relay" to still(relay.running),
                "projection" to still(projection.running),
                "arrivals" to still(arrivals.running),
            ),
        )
    } +
        single { readiness: Readiness ->
            health {
                live("threads") { noDeadlockedThreads() }
                readiness.critical.forEach { (name, check) -> ready(name, timeout = 3.seconds, check = check) }
                readiness.background.forEach { (name, check) ->
                    ready(name, timeout = 3.seconds, critical = false, check = check)
                }
            }
        }
