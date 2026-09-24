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
 * Overriding before cutting, so the registry's settings go too: nothing reaches them any more.
 *
 * `overriding` rather than `plus`: it refuses a key the graph does not already hold, so a fake bound
 * under the wrong type fails here instead of leaving the real client running beside it.
 */
fun shopWith(registry: ChipRegistry = FakeRegistry()): Module =
    petshop.overriding(single<ChipRegistry> { registry }).subgraph<PetShop>()
