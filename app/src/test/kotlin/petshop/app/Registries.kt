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

class FakeRegistry(private val refusing: RegistryError? = null) : ChipRegistry {

    override fun lookup(id: PetId): Either<RegistryError, Chip> = Chip("chip-${id.value}", "Petshop").right()

    override fun transfer(chip: Chip, to: String): Either<RegistryError, Chip> =
        refusing?.left() ?: chip.copy(keeper = to).right()
}

/**
 * Overriding before cutting, so the registry's settings go too. `overriding` rather than `plus`: it
 * refuses a key the graph does not hold, so a fake under the wrong type fails here.
 */
fun shopWith(registry: ChipRegistry = FakeRegistry()): Module =
    petshop.overriding(single<ChipRegistry> { registry }).onAFreshDatabase().subgraph<PetShop>()

fun shopCalling(registry: PelicanWireMock, timeout: Duration = 2.seconds): Module =
    petshop.overriding(single<RegistrySettings> { RegistrySettings(registry.baseUrl, timeout) })
        .onAFreshDatabase()
        .subgraph<PetShop>()
