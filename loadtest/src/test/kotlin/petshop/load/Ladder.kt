package petshop.load

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.assert.shouldBeRight
import io.github.matthewjones372.lark.test.eventually
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.jetty.PelicanServer
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.HttpClientTransport
import io.github.matthewjones372.pelican.test.wiremock.PelicanWireMockExtension
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.StepName
import io.github.matthewjones372.proofload.Tail
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.junit5.LoadTest
import io.github.matthewjones372.proofload.lostGround
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.report.writeHtmlReport
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import io.github.matthewjones372.proofload.warmingUp
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.extension.RegisterExtension
import petshop.api.ShopCodecs
import petshop.api.adoptPet
import petshop.api.listPets
import petshop.app.DatabaseSettings
import petshop.app.Petshop
import petshop.app.RegistrySettings
import petshop.app.TestPostgres
import petshop.app.onDatabase
import petshop.registry.ChipRecord
import petshop.registry.lookupChip
import petshop.registry.recordKeeper
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The ladder behind docs/performance.md, climbed until the shop falls behind. Each rung is written to
 * `stepped-<scenario>.tsv` rather than asserted, so a slow rung is measured, not failed; the only assertion is that no
 * adoption was lost.
 */
class Ladder {

    @JvmField
    @RegisterExtension
    val registry = PelicanWireMockExtension(KotlinxCodecs).apply {
        stub(lookupChip) { petId -> ok(ChipRecord("98100000000000$petId", keeper = "Petshop")) }
        stub(recordKeeper) { (number, keeper) -> ok(ChipRecord(number, keeper.keeper)) }
    }

    // No arrivals, so every rung browses the same catalogue and adopts from the shelf it was stocked with.
    private fun shop(database: DatabaseSettings): Module = Petshop.module
        .overriding(single<RegistrySettings> { RegistrySettings(registry.baseUrl, 2.seconds) })
        .onDatabase(database)
        .overridingConfig("petshop.arrivalsEvery = 1h")

    private val browse = step("browse the shop")

    private val adopt = step("adopt a pet")

    @LoadTest
    fun `browsing, stepped up until the shop falls behind`(proofload: Proofload) {
        shop(TestPostgres.fresh().stocked(pets = CATALOGUE)).use { server: PelicanServer ->
            client(server).use { client ->
                val browsing = scenario("browsing") {
                    exec(browse) { step ->
                        if (!client.response(listPets, Unit).isSuccess) step.fail("the shop did not answer")
                    }
                }
                climb("browsing", browse) { rate, warming ->
                    proofload.run(browsing.at(rate.perSecond, over = HELD).warmingUp(warming))
                }
            }
        }.shouldBeRight()
    }

    /**
     * A shop of its own per rung, stocked with only the pets that rung adopts: the shop's actor copies its whole map
     * on every adoption, so one shelf for the ladder would measure the shelf's size rather than the server.
     */
    @LoadTest
    fun `adoption, stepped up until the shop falls behind`(proofload: Proofload) {
        climb("adoption", adopt) { rate, warming ->
            val database = TestPostgres.fresh().stocked(pets = rate * ((warming + HELD).inWholeSeconds.toInt() + 1))
            val next = AtomicLong()
            val adopted = AtomicLong()
            val answered = AtomicLong()
            shop(database).use { server: PelicanServer ->
                client(server).use { client ->
                    val adopting = scenario("adoption") {
                        exec(adopt) { step ->
                            val answer = client.outcome(adoptPet, next.incrementAndGet())
                            answered.incrementAndGet()
                            when (answer) {
                                is Outcome.Ok -> adopted.incrementAndGet()
                                is Outcome.Err -> step.fail("refused: ${answer.error}")
                            }
                        }
                    }
                    val result = proofload.run(adopting.at(rate.perSecond, over = HELD).warmingUp(warming))
                    // A refusal from the registry hands the pet back as a separate message, so the table settles after.
                    // A request that timed out may still have adopted its pet, so only a fully answered run matches.
                    eventually(10.seconds) {
                        val shelf = database.adoptedOnTheShelf()
                        if (answered.get() == next.get()) {
                            shelf shouldBe adopted.get()
                        } else {
                            shelf shouldBeGreaterThanOrEqual adopted.get()
                            shelf shouldBeLessThanOrEqual next.get()
                        }
                    }
                    result
                }
            }.shouldBeRight()
        }
    }

    private fun climb(name: String, measured: StepName, rung: (rate: Int, warming: Duration) -> RunResult) {
        val reports = Files.createDirectories(REPORTS)
        var table: Path? = null
        for ((index, rate) in LADDER.withIndex()) {
            val result = rung(rate, if (index == 0) FIRST_WARM_UP else WARM_UP)
            result.writeHtmlReport(reports.resolve("$name-$rate.html"))

            val written = table ?: reports.resolve("stepped-$name.tsv").also { Files.writeString(it, COLUMNS) }
            table = written
            val verdict = result.verdict(measured)
            Files.writeString(written, result.row(name, rate, measured, verdict), CREATE, APPEND)
            if (verdict == Verdict.FellBehind) break
        }
    }

    // HTTP/1.1: the JDK client's default asks the server for an h2c upgrade on every connection.
    private fun client(server: PelicanServer): ApiClient = ApiClient(
        HttpClientTransport(
            server.baseUrl,
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build(),
        ),
        ShopCodecs,
    )
}

private val LADDER = listOf(100, 250, 500, 1000, 2000, 3000, 4000, 5000, 6000, 8000, 10000, 12000)

private val HELD: Duration = 10.seconds

private val WARM_UP: Duration = 5.seconds

/** The first rung also warms a cold JVM, its pool and the JIT. */
private val FIRST_WARM_UP: Duration = 10.seconds

private const val CATALOGUE = 100

/** Above this p99 service time, or with more than 1% failed, the shop fell behind the rate. */
private val P99_CEILING: Duration = 100.milliseconds

private const val FAILURE_CEILING = 0.01

private enum class Verdict {
    KeptUp,
    FellBehind,

    /** The generator, which shares the JVM, slipped its own schedule: the load arrived less evenly than planned. */
    NotOffered,
}

private val COLUMNS = listOf(
    "scenario", "rate", "requests", "failed", "p50_ms", "p99_ms", "p999_ms", "max_ms",
    "behind_p99_ms", "verdict",
).joinToString("\t", postfix = "\n")

private fun RunResult.verdict(measured: StepName): Verdict = when {
    failed > count * FAILURE_CEILING || this[measured].serviceTime.p99 >= P99_CEILING -> Verdict.FellBehind
    lostGround() -> Verdict.NotOffered
    else -> Verdict.KeptUp
}

private fun RunResult.row(name: String, rate: Int, measured: StepName, verdict: Verdict): String {
    val service = this[measured].serviceTime
    val p999 = when (val tail = service.p999) {
        is Tail.Measured -> tail.duration.ms()
        is Tail.Absent -> "-"
    }
    return listOf(
        name, rate, count, failed,
        service.p50.ms(), service.p99.ms(), p999, service.max.ms(), behind.p99.ms(), verdict,
    ).joinToString("\t", postfix = "\n")
}

private fun Duration.ms(): String = "%.2f".format(inWholeMicroseconds / 1000.0)

/** The changelog adopts a pets table that already exists, so a table stocked first is the shelf the shop opens with. */
private fun DatabaseSettings.stocked(pets: Int): DatabaseSettings = also {
    DriverManager.getConnection(url, user, password.reveal()).use { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE pets (id BIGINT PRIMARY KEY, name TEXT NOT NULL, species TEXT NOT NULL, " +
                    "adopted BOOLEAN NOT NULL)",
            )
            statement.execute(
                "INSERT INTO pets SELECT n, 'Pet ' || n, (ARRAY['Cat', 'Dog', 'Parrot', 'Tortoise'])[n % 4 + 1], " +
                    "false FROM generate_series(1, $pets) AS n",
            )
        }
    }
}

private fun DatabaseSettings.adoptedOnTheShelf(): Long =
    DriverManager.getConnection(url, user, password.reveal()).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM pets WHERE standing <> 'on_shelf'").use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
    }
