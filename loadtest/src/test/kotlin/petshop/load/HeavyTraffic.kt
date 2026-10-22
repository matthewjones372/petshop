package petshop.load

import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.Transport
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Simulation
import io.github.matthewjones372.proofload.Tail
import io.github.matthewjones372.proofload.Timing
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.feed
import io.github.matthewjones372.proofload.generatorSaturated
import io.github.matthewjones372.proofload.hold
import io.github.matthewjones372.proofload.inFlightAtMost
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.rampRate
import io.github.matthewjones372.proofload.report.writeHtmlReport
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.github.matthewjones372.proofload.stages
import io.github.matthewjones372.proofload.step
import io.github.matthewjones372.proofload.then
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.StringRequestContent
import org.eclipse.jetty.io.ByteBufferPool
import petshop.api.ProblemDto
import petshop.api.ShopCodecs
import petshop.api.adoptPet
import petshop.api.getPet
import petshop.api.listPets
import java.nio.file.Files
import java.nio.file.Path
import java.util.SplittableRandom
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A ramp to PEAK requests a second against a running shop, then a hold there. loadtest/heavy.sh stocks the shop with
 * PETS pets and runs this; docs/heavy-load.md is what it found.
 */
fun main() {
    val target = System.getenv("PETSHOP_URL") ?: "http://localhost:8080"
    val peak = System.getenv("PEAK")?.toInt() ?: 50_000
    val ramp = System.getenv("RAMP")?.let(Duration::parse) ?: 60.seconds
    val held = System.getenv("HOLD")?.let(Duration::parse) ?: 60.seconds
    val pets = System.getenv("PETS")?.toLong() ?: 100L
    val timeout = System.getenv("TIMEOUT")?.let(Duration::parse) ?: 2.seconds
    val reports = Path.of(System.getenv("REPORTS") ?: "build/reports/proofload/heavy")

    val lookAtAPet = step("GET /pets/{id}")
    val browse = step("GET /pets")
    val adopt = step("POST /pets/{id}/adoption")

    val nextToAdopt = AtomicLong()
    val taken = AtomicLong()
    val refused = AtomicLong()

    JettyTransport(target, timeout).use { transport ->
        val client = ApiClient(transport, ShopCodecs)
        fun anyPet(): Long = ThreadLocalRandom.current().nextLong(1, pets + 1)

        val visitor = scenario("a visitor") {
            doIf({ it[ROLL]!! < LOOK_AT_A_PET }) {
                exec(lookAtAPet) { step ->
                    if (!client.response(getPet, anyPet()).isSuccess) step.fail("the shop did not answer")
                }
            }
            doIf({ it[ROLL]!! in LOOK_AT_A_PET until LOOK_AT_A_PET + BROWSE }) {
                exec(browse) { step ->
                    if (!client.response(listPets, Unit).isSuccess) step.fail("the shop did not answer")
                }
            }
            doIf({ it[ROLL]!! >= LOOK_AT_A_PET + BROWSE }) {
                exec(adopt) { step ->
                    // Each pet on the shelf once, so every adoption writes until the shelf is empty.
                    val id = nextToAdopt.incrementAndGet().takeIf { it <= pets } ?: anyPet()
                    when (val answer = client.outcome(adoptPet, id)) {
                        is Outcome.Ok -> taken.incrementAndGet()

                        is Outcome.Err -> when (answer.error) {
                            is ProblemDto.AlreadyAdopted,
                            is ProblemDto.BeingAdopted,
                            is ProblemDto.NoSuchPet,
                            is ProblemDto.NotChipped,
                            -> refused.incrementAndGet()

                            is ProblemDto.RegistryDown, is ProblemDto.NotRecorded ->
                                step.fail("the shop could not finish: ${answer.error}")
                        }
                    }
                }
            }
        }

        val profile = rampRate(from = START.perSecond, to = peak.perSecond, over = ramp) then hold(peak.perSecond, held)
        println("Visiting $target: up to $peak a second over $ramp, then held for $held, against $pets pets")
        val mix = feed(ROLL) { user -> SplittableRandom(user).nextInt(MIX) }
        val result = Proofload().run(Simulation(visitor, profile, mix).inFlightAtMost(CONNECTIONS))

        Files.createDirectories(reports)
        result.writeHtmlReport(reports.resolve("heavy.html"))
        Files.writeString(reports.resolve("seconds.tsv"), result.seconds(peak, ramp))
        println(result.summary(listOf(lookAtAPet.name, browse.name, adopt.name)))
        if (result.generatorSaturated()) {
            println("Void: the generator refused ${result.refused} arrivals at its cap of $CONNECTIONS in flight")
        }
        println("Adoptions: ${taken.get()} taken, ${refused.get()} refused as declared")
        println("Report at ${reports.resolve("heavy.html")}, each second in ${reports.resolve("seconds.tsv")}")
    }
}

/**
 * Jetty's client, with a connection for each of the [CONNECTIONS] users the simulation lets be in flight, so no request
 * waits in Jetty's queue. The cap is the engine's: an arrival past it is refused and counted in `result.refused`, never
 * kept in this process. Waiting does not scale: Jetty's own queue and a timed semaphore both unlink a waiter that times
 * out by walking the queue, and 40,000 of them took every core and then the heap. Not the JDK's client: it opens a
 * connection per request in flight, which takes every ephemeral port.
 */
private class JettyTransport(target: String, private val timeout: Duration) :
    Transport,
    AutoCloseable {

    private val base = target.trimEnd('/')

    private val client = HttpClient().apply {
        maxConnectionsPerDestination = CONNECTIONS
        // Pooled buffers count what they retain with a scan per release: a fifth of this process's CPU at 50k/s.
        byteBufferPool = ByteBufferPool.NON_POOLING
        start()
    }

    override fun send(request: RequestSpec): ResponseSpec {
        val response = client.newRequest(base + request.target)
            .method(request.method.name)
            .timeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .headers { headers -> request.headers.forEach { (name, value) -> headers.add(name, value) } }
            .apply { request.body?.let { body(StringRequestContent("application/json", it)) } }
            .send()
        return ResponseSpec(response.status, response.headers.map { it.name to it.value }, response.contentAsString)
    }

    override fun close() = client.stop()
}

private val ROLL = sessionKey<Int>("roll")

/** The mix, in thousandths of visitors; what is left of [MIX] adopts. */
private const val MIX = 1000
private const val LOOK_AT_A_PET = 740
private const val BROWSE = 250

/**
 * A few dozen are in use at 50,000 a second and a fraction of a millisecond each; this many covers half of a 40 ms GC
 * pause in the shop. Jetty scans its pool of connections on every request, so many more would cost the generator CPU
 * it shares with the shop.
 */
private const val CONNECTIONS = 1024

/** A ramp starts above nothing. */
private const val START = 100

private fun RunResult.summary(steps: List<String>): String = buildString {
    appendLine("step\trequests\tfailed\tp50_ms\tp99_ms\tp999_ms\tmax_ms")
    steps.filter { it in this@summary.steps }.forEach { name ->
        val stats = this@summary[name]
        appendLine("$name\t${stats.count}\t${stats.failed.count}\t${stats.serviceTime.row()}")
    }
    stages.forEach { stage ->
        val seconds = (stage.until - stage.from).inWholeSeconds.coerceAtLeast(1)
        appendLine(
            "stage ${stage.index + 1} of ${stage.of}: ${stage.count} requests (${stage.count / seconds}/s), " +
                "${stage.failed} failed, service p50/p99/p99.9/max ms ${stage.serviceTime.row().replace('\t', '/')}",
        )
    }
    appendLine("not sent: generator saturated, $refused")
    append("generator: behind p99 ${behind.p99.ms()} ms, its own pauses p99 ${hiccups.p99.ms()} ms")
}

/** One row a second: what the profile asked for, what came back, and how long it took. */
private fun RunResult.seconds(peak: Int, ramp: Duration): String = buildString {
    appendLine("second\tasked\tanswered\tfailed\trefused\tp50_ms\tp99_ms\tin_flight")
    timeline.forEachIndexed { second, slot ->
        val asked = if (second < ramp.inWholeSeconds) START + (peak - START) * second / ramp.inWholeSeconds else peak
        val inFlight = usersInFlight.getOrNull(second)?.toString() ?: "-"
        val refused = refusedPerSecond.getOrNull(second) ?: 0L
        val row = listOf(
            second,
            asked,
            slot.count,
            slot.failed,
            refused,
            slot.p50.ms(),
            slot.serviceTime.p99.ms(),
            inFlight,
        )
        appendLine(row.joinToString("\t"))
    }
}

private fun Timing.row(): String {
    val p999 = when (val tail = p999) {
        is Tail.Measured -> tail.duration.ms()
        is Tail.Absent -> "-"
    }
    return listOf(p50.ms(), p99.ms(), p999, max.ms()).joinToString("\t")
}

private fun Duration.ms(): String = "%.2f".format(inWholeMicroseconds / 1000.0)
