package petshop.app

import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.fail.fold
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.pelican.health.Check
import io.github.matthewjones372.pelican.health.Status
import io.github.matthewjones372.pelican.health.health
import io.github.matthewjones372.pelican.health.jdbc
import io.github.matthewjones372.pelican.health.noDeadlockedThreads
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
    single { pool: DataSource, ref: ActorRef<Shop>, relay: OutboxRelay, projection: Projection, arrivals: Arrivals ->
        Readiness(
            critical = mapOf(
                "database" to Check { jdbc(pool, timeout = 2.seconds) },
                // Within Pelican's 3s check timeout, so the check answers with its own reason.
                "shop" to Check {
                    ref.answers(within = 2.seconds).fold(
                        { failure -> Status.Fail("the shop did not answer: $failure") },
                        { Status.Pass },
                    )
                },
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
