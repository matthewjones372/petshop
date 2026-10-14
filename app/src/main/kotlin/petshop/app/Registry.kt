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

/** Where the registry is and how long the shop waits for it. */
data class RegistrySettings(val baseUrl: String, val timeout: Duration)

/**
 * The shop's side of the registry, over the client generated from `registrySpec()`. The path, the
 * body and the declared statuses are the generated client's; what is left here is the one decision
 * that is the shop's own — which answers mean "no chip" and which mean "could not ask".
 *
 * Requests go out on the shop's own actor system, so there is one HTTP stack in the process, through a
 * circuit breaker of lark's.
 */
class HttpChipRegistry(settings: RegistrySettings, system: ActorSystem) : ChipRegistry {

    private val client = RegistryClient(
        baseUrl = settings.baseUrl,
        codecs = JacksonCodecs,
        transport = PekkoHttpTransport(system),
        timeout = settings.timeout.toJavaDuration(),
    )

    /**
     * After five calls in a row that throw, the breaker opens and the shop stops asking for a while: an adoption is
     * refused at once as the registry being down, rather than each one waiting out the timeout first. A declared
     * 404 is an answer, not a throw, so a run of unchipped pets never opens it. Its state is `lark.breaker.state`
     * on /metrics, and every call is in `lark.policy.calls`.
     */
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

    /**
     * A declared 404 is the registry saying it has no such chip. Everything else that is not a chip —
     * a 500 the contract never declared, a reset connection, a wait past the timeout — is the registry
     * not answering, and none of it says anything about the pet.
     */
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
