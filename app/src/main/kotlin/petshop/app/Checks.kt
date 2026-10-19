package petshop.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.pelican.health.Check
import io.github.matthewjones372.pelican.health.Status
import io.github.matthewjones372.pelican.health.health
import io.github.matthewjones372.pelican.health.jdbc
import io.github.matthewjones372.pelican.health.noDeadlockedThreads
import petshop.domain.PetShop
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

/** A failing [critical] check answers 503; a failing [background] one is a warning, still a 200. */
class Readiness(val critical: Map<String, Check>, val background: Map<String, Check> = emptyMap()) {

    val checks: Map<String, Check> get() = critical + background

    /** A throw is a failure and a warning is not, as `/health/ready` counts them. */
    fun failing(): Set<String> = checks.filterValues { check -> !passes(check) }.keys

    @Suppress("TooGenericExceptionCaught")
    private fun passes(check: Check): Boolean = try {
        check.check() !is Status.Fail
    } catch (_: Exception) {
        false
    }
}

private fun still(running: Running<*, *>): Check = Check {
    val exit = running.exit.toCompletableFuture()
    if (exit.isDone) Status.Fail("ended: ${exit.join()}") else Status.Pass
}

val checks: Module =
    single { pool: DataSource, shop: PetShop, relay: OutboxRelay, projection: Projection, arrivals: Arrivals ->
        Readiness(
            critical = mapOf(
                "database" to Check { jdbc(pool, timeout = 2.seconds) },
                // An ask that times out throws, and Pelican counts a throw as a fail.
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
