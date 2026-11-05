package petshop.app

import dev.surefold.CircuitBreaker
import dev.surefold.Limit
import dev.surefold.Schedule
import dev.surefold.app.Module
import dev.surefold.app.boundTo
import dev.surefold.app.single
import dev.surefold.app.singleOf
import dev.surefold.app.typesafe.config
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.flatMap
import dev.surefold.fail.left
import dev.surefold.fail.mapLeft
import dev.surefold.fail.right
import dev.surefold.guarded
import dev.surefold.policy
import io.github.matthewjones372.pelican.client.jetty.JettyHttpTransport
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.github.matthewjones372.pelican.metrics.otel.traced
import io.opentelemetry.sdk.OpenTelemetrySdk
import petshop.domain.Chip
import petshop.domain.ChipRegistry
import petshop.domain.NotRegistered
import petshop.domain.PetId
import petshop.domain.RegistryError
import petshop.domain.Unreachable
import petshop.registry.client.ChipRecord
import petshop.registry.client.NewKeeper
import petshop.registry.client.Outcome
import petshop.registry.client.RegistryClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

data class RegistrySettings(val baseUrl: String, val timeout: Duration)

/**
 * One [atOnce] for every call, so adoptions and the settling stream share the registry's budget. A call past it
 * waits for a permit rather than being refused: each call ahead ends within the client's timeout.
 */
class HttpChipRegistry(
    settings: RegistrySettings,
    transport: JettyHttpTransport,
    telemetry: OpenTelemetrySdk,
    private val atOnce: Limit,
) : ChipRegistry {

    private val client = RegistryClient(
        baseUrl = settings.baseUrl,
        codecs = KotlinxCodecs,
        // A client span per call, sending traceparent so the registry continues the adoption's trace.
        transport = transport.traced(telemetry),
        timeout = settings.timeout.toJavaDuration(),
    )

    /** Only throws count as failures; a declared 404 is an answer, so unchipped pets never open the breaker. */
    private val calls = policy("chip-registry") {
        guard(
            CircuitBreaker(
                name = "chip-registry",
                maxFailures = 5,
                resetAfter = Schedule.exponential<Unit>(
                    1.seconds,
                ).jittered(0.8, 1.2).delayed { _, wait -> minOf(wait, 30.seconds) },
            ),
        )
    }

    override fun lookup(id: PetId): Either<RegistryError, Chip> = asking { client.lookupChip(id.value) }

    override fun transfer(chip: Chip, to: String): Either<RegistryError, Chip> =
        asking { client.recordKeeper(chip.number, NewKeeper(to)) }

    /** Only a declared 404 means no chip; anything else that is not a chip means the registry did not answer. */
    private fun asking(call: () -> Outcome<*, ChipRecord>): Either<RegistryError, Chip> = Either.catch {
        attempt<RegistryError, Outcome<*, ChipRecord>> {
            guarded(
                calls,
                ifRejected = { refused -> Unreachable("${refused.guard} refused: ${refused.message}") },
            ) {
                // Inside the breaker, so an open one refuses without queueing for a permit.
                atOnce.withPermit(call)
            }
        }
    }
        .mapLeft { failed -> Unreachable(failed.message ?: failed.toString()) }
        .flatMap { it }
        .flatMap { answer ->
            when (answer) {
                is Outcome.Ok -> Chip(answer.value.number, answer.value.keeper).right()
                is Outcome.Err -> NotRegistered.left()
            }
        }
}

val registry: Module =
    config<RegistrySettings>("petshop.registry") { RegistrySettings(string("baseUrl"), duration("timeout")) } +
        single<Limit> { Limit.adaptive("chip-registry") } +
        singleOf(::HttpChipRegistry).boundTo<ChipRegistry>()
