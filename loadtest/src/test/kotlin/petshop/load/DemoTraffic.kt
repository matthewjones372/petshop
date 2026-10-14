package petshop.load

import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.report.writeHtmlReport
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import java.nio.file.Path
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import petshop.api.adoptPet
import petshop.api.listPets
import petshop.api.getPet
import petshop.api.stats

/**
 * Demo visitors against `PETSHOP_URL` (http://localhost:8080 by default), at `RATE` a second for `DURATION`:
 * `./gradlew :loadtest:demoTraffic`. Declared refusals are answers, not failures; only an undeclared
 * response fails a step.
 */
fun main() {
    val target = System.getenv("PETSHOP_URL") ?: "http://localhost:8080"
    val rate = System.getenv("RATE")?.toInt() ?: DEFAULT_RATE
    val duration = System.getenv("DURATION")?.let(Duration::parse) ?: 30.minutes

    val lookAtAPet = step("look at a pet")
    val adopt = step("try to adopt one")
    val readTheStats = step("read the stats")

    apiClient(target, JacksonCodecs).use { client ->
        // The newest pet now, asked once; after that the newest grows by one each arrival.
        val newestAtStart = client.call(listPets, Unit).maxOf { it.id }
        val started = TimeSource.Monotonic.markNow()
        fun recentPet(): Long {
            val newest = newestAtStart + started.elapsedNow().inWholeSeconds / SECONDS_PER_ARRIVAL
            return Random.nextLong(maxOf(1, newest - RECENT_PETS), newest + 1)
        }

        val visitor = scenario("a visitor") {
            exec(lookAtAPet) { _ -> client.outcome(getPet, recentPet()) }
            doIf({ Random.nextInt(ADOPT_ONE_IN) == 0 }) {
                exec(adopt) { _ ->
                    val id = when (Random.nextInt(ODDS)) {
                        0 -> NO_SUCH_PET
                        1 -> MRS_PEEL
                        else -> recentPet()
                    }
                    client.outcome(adoptPet, id)
                }
            }
            exec(readTheStats) { step -> if (!client.response(stats, Unit).isSuccess) step.fail("no stats") }
        }

        println("Visiting $target: $rate a second for $duration")
        val result = Proofload().run(visitor.at(rate.perSecond, over = duration))
        result.writeHtmlReport(Path.of("build/reports/proofload/demo-traffic.html"))
        println("Done: ${result.failed} failed. Report at build/reports/proofload/demo-traffic.html")
    }
}

private const val DEFAULT_RATE = 5
private const val ADOPT_ONE_IN = 4

/** The arrivals' pace in the demo's application.conf: one pet every five seconds. */
private const val SECONDS_PER_ARRIVAL = 5

private const val RECENT_PETS = 300L

/** One adoption in twenty for a pet that is not there, and one in twenty for Mrs Peel, who has no chip. */
private const val ODDS = 20
private const val NO_SUCH_PET = 999_999_999L
private const val MRS_PEEL = 3L
