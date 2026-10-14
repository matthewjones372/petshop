package petshop.load

import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.retry
import io.github.matthewjones372.pelican.test.ApiClient
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import petshop.api.KotlinxShopCodecs
import petshop.api.adoptPet
import petshop.api.getPet
import petshop.api.listPets
import petshop.api.stats

/** Declared refusals are answers, not failures: only an undeclared response fails a step. */
fun main() {
    val target = System.getenv("PETSHOP_URL") ?: "http://localhost:8080"
    val rate = System.getenv("RATE")?.toInt() ?: DEFAULT_RATE
    val duration = System.getenv("DURATION")?.let(Duration::parse) ?: 30.minutes

    val lookAtAPet = step("look at a pet")
    val adopt = step("try to adopt one")
    val readTheStats = step("read the stats")

    apiClient(target, KotlinxShopCodecs).use { client ->
        // The shop runs on the host and may start after this container: wait for it rather than exit.
        println("Waiting for the shop at $target")
        // The newest pet now, asked once; after that the newest grows by one each arrival.
        val newestAtStart = newestPet(client)
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

/**
 * The newest pet's id. Its own function, not inline in `main`: the shop has no call for one pet, so this reads every
 * pet, and an inlined read leaves the whole list in a slot of the frame that runs for the day.
 */
private fun newestPet(client: ApiClient): Long =
    Schedule.spaced<Throwable>(2.seconds).retry { client.call(listPets, Unit) }.maxOf { it.id }

private const val DEFAULT_RATE = 5
private const val ADOPT_ONE_IN = 4

/** The arrivals' pace in the demo's application.conf: one pet every five seconds. */
private const val SECONDS_PER_ARRIVAL = 5

private const val RECENT_PETS = 300L

/** One adoption in twenty for a pet that is not there, and one in twenty for Mrs Peel, who has no chip. */
private const val ODDS = 20
private const val NO_SUCH_PET = 999_999_999L
private const val MRS_PEEL = 3L
