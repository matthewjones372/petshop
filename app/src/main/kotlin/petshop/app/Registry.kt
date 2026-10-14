package petshop.app

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.github.matthewjones372.lark.CircuitBreaker
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.typesafe.config
import io.github.matthewjones372.lark.guarded
import io.github.matthewjones372.lark.policy
import io.github.matthewjones372.pelican.client.pekko.PekkoHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import org.apache.pekko.actor.ActorSystem
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

data class RegistrySettings(val baseUrl: String, val timeout: Duration)

class HttpChipRegistry(settings: RegistrySettings, system: ActorSystem) : ChipRegistry {

    private val client = RegistryClient(
        baseUrl = settings.baseUrl,
        codecs = JacksonCodecs,
        transport = PekkoHttpTransport(system),
        timeout = settings.timeout.toJavaDuration(),
    )

    /** Only throws count as failures; a declared 404 is an answer, so unchipped pets never open the breaker. */
    private val calls = policy("chip-registry") {
        guard(
            CircuitBreaker(
                name = "chip-registry",
                maxFailures = 5,
                resetAfter = Schedule.exponential<Unit>(1.seconds).jittered(0.8, 1.2).delayed { _, wait -> minOf(wait, 30.seconds) },
            ),
        )
    }

    override fun lookup(id: PetId): Either<RegistryError, Chip> =
        asking { client.lookupChip(id.value) }

    override fun transfer(chip: Chip, to: String): Either<RegistryError, Chip> =
        asking { client.recordKeeper(chip.number, NewKeeper(to)) }

    /** Only a declared 404 means no chip; anything else that is not a chip means the registry did not answer. */
    private fun asking(call: () -> Outcome<*, ChipRecord>): Either<RegistryError, Chip> =
        Either.catch {
            either<RegistryError, Outcome<*, ChipRecord>> {
                guarded(calls, ifRejected = { refused -> Unreachable("${refused.guard} refused: ${refused.message}") }) {
                    call()
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
        singleOf(::HttpChipRegistry).boundTo<ChipRegistry>()
