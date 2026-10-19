package petshop.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.pelican.test.wiremock.PelicanWireMock
import petshop.domain.Chip
import petshop.domain.ChipRegistry
import petshop.domain.NotRegistered
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.RegistryError
import petshop.domain.Unreachable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class FakeRegistry(private val refusing: RegistryError? = null) : ChipRegistry {

    override fun lookup(id: PetId): Either<RegistryError, Chip> = Chip("chip-${id.value}", "Petshop").right()

    override fun transfer(chip: Chip, to: String): Either<RegistryError, Chip> =
        refusing?.left() ?: chip.copy(keeper = to).right()
}

/**
 * Remembers each chip's keeper. [transfers] says what transfer number n does, and [answers] whether a pet's lookup
 * number n answers, so a test can lose an answer, lose a request, or take the registry down and bring it back.
 * [unregistered] pets have no chip at all.
 */
class ScriptedRegistry(
    private val transfers: (n: Int) -> Transfer = { Transfer.Records },
    private val answers: (id: PetId, n: Int) -> Boolean = { _, _ -> true },
    keepers: Map<PetId, String> = emptyMap(),
    private val unregistered: Set<PetId> = emptySet(),
) : ChipRegistry {

    enum class Transfer { Records, RecordsButLosesTheAnswer, LosesTheRequest }

    private val keepers = ConcurrentHashMap(keepers.mapKeys { (id, _) -> "chip-${id.value}" })
    private val lookups = ConcurrentHashMap<PetId, AtomicInteger>()
    private val transferred = AtomicInteger()

    fun keeperOf(id: PetId): String = keepers["chip-${id.value}"] ?: "Petshop"

    override fun lookup(id: PetId): Either<RegistryError, Chip> {
        val n = lookups.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
        return when {
            !answers(id, n) -> Unreachable("lookup $n of pet ${id.value} timed out").left()
            id in unregistered -> NotRegistered.left()
            else -> Chip("chip-${id.value}", keeperOf(id)).right()
        }
    }

    override fun transfer(chip: Chip, to: String): Either<RegistryError, Chip> {
        val n = transferred.incrementAndGet()
        return when (transfers(n)) {
            Transfer.Records -> chip.copy(keeper = to).also { keepers[chip.number] = to }.right()

            Transfer.RecordsButLosesTheAnswer -> {
                keepers[chip.number] = to
                Unreachable("transfer $n timed out").left()
            }

            Transfer.LosesTheRequest -> Unreachable("transfer $n timed out").left()
        }
    }
}

/** `overriding`, not `plus`: it refuses a key the graph does not hold, so a fake under the wrong type fails here. */
fun shopWith(registry: ChipRegistry = FakeRegistry()): Module =
    Petshop.module.overriding(single<ChipRegistry> { registry }).onAFreshDatabase().subgraph<PetShop>()

fun shopCalling(registry: PelicanWireMock, timeout: Duration = 2.seconds): Module =
    Petshop.module.overriding(single<RegistrySettings> { RegistrySettings(registry.baseUrl, timeout) })
        .onAFreshDatabase()
        .subgraph<PetShop>()
