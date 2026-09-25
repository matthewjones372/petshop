package petshop.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import petshop.domain.Chip
import petshop.domain.ChipRegistry
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.RegistryError
import io.github.matthewjones372.pelican.test.wiremock.PelicanWireMock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A registry that knows every pet and records every keeper, for a test about the shop rather than
 * about the registry. [refusing] is what it answers a transfer with instead, when a test wants one
 * refused.
 */
class FakeRegistry(private val refusing: RegistryError? = null) : ChipRegistry {

    override fun lookup(id: PetId): Either<RegistryError, Chip> = Chip("chip-${id.value}", "Petshop").right()

    override fun transfer(chip: Chip, to: String): Either<RegistryError, Chip> =
        refusing?.left() ?: chip.copy(keeper = to).right()
}

/**
 * The shop with the registry swapped at the node, and cut down to what [PetShop] is reached through.
 * Overriding before cutting, so the registry's settings go too: nothing reaches them any more. The
 * outbox is a real table, in a schema nothing else writes to.
 *
 * `overriding` rather than `plus`: it refuses a key the graph does not already hold, so a fake bound
 * under the wrong type fails here instead of leaving the real client running beside it.
 */
fun shopWith(registry: ChipRegistry = FakeRegistry()): Module =
    petshop.overriding(single<ChipRegistry> { registry }).onAFreshDatabase().subgraph<PetShop>()

/**
 * The shop exactly as `main` starts it — the real client, its JSON and its timeout — calling
 * [registry] instead of the real registry. The override is the settings node itself, typed, so there
 * is no configuration text to get wrong and nothing else about the graph changes.
 */
fun shopCalling(registry: PelicanWireMock, timeout: Duration = 2.seconds): Module =
    petshop.overriding(single<RegistrySettings> { RegistrySettings(registry.baseUrl, timeout) })
        .onAFreshDatabase()
        .subgraph<PetShop>()
