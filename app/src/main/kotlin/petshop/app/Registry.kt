package petshop.app

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.typesafe.config
import io.github.matthewjones372.pelican.client.pekko.PekkoHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
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
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** Where the registry is and how long the shop waits for it. */
data class RegistrySettings(val baseUrl: String, val timeout: Duration)

/**
 * The shop's side of the registry, over the client generated from `registrySpec()`. The path, the
 * body and the declared statuses are the generated client's; what is left here is the one decision
 * that is the shop's own — which answers mean "no chip" and which mean "could not ask".
 *
 * Requests go out on the shop's own actor system, so there is one HTTP stack in the process.
 */
class HttpChipRegistry(settings: RegistrySettings, system: ActorSystem) : ChipRegistry {

    private val client = RegistryClient(
        baseUrl = settings.baseUrl,
        codecs = JacksonCodecs,
        transport = PekkoHttpTransport(system),
        timeout = settings.timeout.toJavaDuration(),
    )

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
        Either.catch(call)
            .mapLeft { failed -> Unreachable(failed.message ?: failed.toString()) }
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
