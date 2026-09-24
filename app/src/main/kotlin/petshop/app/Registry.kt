package petshop.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.typesafe.config
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.client.pekko.PekkoHttpTransport
import org.apache.pekko.actor.ActorSystem
import petshop.domain.Chip
import petshop.domain.ChipRegistry
import petshop.domain.NotRegistered
import petshop.domain.PetId
import petshop.domain.RegistryError
import petshop.domain.Unreachable
import java.net.URLEncoder
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** Where the registry is and how long the shop waits for it. A test changes the first; so might an outage. */
data class RegistrySettings(val baseUrl: String, val timeout: Duration)

/**
 * The shop's client for the registry, hand-written the way most clients for somebody else's API are.
 * That is the code a WireMock test is for: the path, the body, the statuses and the timeout are all
 * decisions made here, and a fake [ChipRegistry] would skip every one of them.
 *
 * Requests go out on the shop's own actor system, so there is one HTTP stack in the process.
 */
class HttpChipRegistry(settings: RegistrySettings, system: ActorSystem) : ChipRegistry {

    private val base = settings.baseUrl.trimEnd('/')
    private val timeout = settings.timeout
    private val transport = PekkoHttpTransport(system)
    private val json = jacksonObjectMapper()

    override fun lookup(id: PetId): Either<RegistryError, Chip> =
        exchange(ClientRequest(Method.GET, "$base/chips/${id.value}", accepting))

    override fun transfer(chip: Chip, to: String): Either<RegistryError, Chip> =
        exchange(
            ClientRequest(
                Method.POST,
                "$base/chips/${URLEncoder.encode(chip.number, Charsets.UTF_8)}/keeper",
                accepting + ("Content-Type" to "application/json"),
                ClientRequest.Body.Text(json.writeValueAsString(Keeper(to))),
            ),
        )

    /**
     * One call, and every way it can go wrong folded into two answers: the registry does not know the
     * chip, or the registry could not say. A 500, a reset connection and a wait past [timeout] are all
     * the second one, because none of them tells the shop anything about the pet.
     */
    private fun exchange(request: ClientRequest): Either<RegistryError, Chip> {
        val response: ClientResponse = try {
            transport.send(request.withTimeout(timeout.toJavaDuration()))
                .toCompletableFuture()
                // A second deadline, in case the transport's own is the thing that is stuck.
                .get(timeout.inWholeMilliseconds * 2, TimeUnit.MILLISECONDS)
        } catch (failed: ExecutionException) {
            return Unreachable("$request: ${failed.cause ?: failed}").left()
        } catch (failed: java.util.concurrent.TimeoutException) {
            return Unreachable("$request: no answer in $timeout").left()
        }
        return when (response.status) {
            200 -> json.readValue<Chip>(response.text()).right()
            404 -> NotRegistered.also { response.body.close() }.left()
            else -> Unreachable("$request answered ${response.status}: ${response.text().take(200)}").left()
        }
    }

    private data class Keeper(val keeper: String)

    private companion object {
        val accepting = listOf("Accept" to "application/json")
    }
}

val registry: Module =
    config<RegistrySettings>("petshop.registry") { RegistrySettings(string("baseUrl"), duration("timeout")) } +
        singleOf(::HttpChipRegistry).boundTo<ChipRegistry>()
