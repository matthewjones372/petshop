package petshop.api

import arrow.core.Either
import arrow.core.left
import io.github.matthewjones372.pelican.test.golden.Golden
import io.github.matthewjones372.pelican.test.pekko.inMemory
import org.junit.jupiter.api.Test
import petshop.domain.NoSuchPet
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.PetShopError
import petshop.domain.Species

/**
 * What the shop's callers were promised, recorded under `src/test/resources/golden`.
 *
 * A change that would break somebody already calling — a removed status, a new required field, a
 * renamed path — fails here and says so. A change that costs them nothing rewrites the file and
 * passes, and the rewritten file is in the diff for a reviewer to read.
 */
class GoldenSpec {

    private val golden = Golden()

    private val nibbles = Pet(PetId(1), "Nibbles", Species.Tortoise)

    private val shop = object : PetShop {
        override fun all(): List<Pet> = listOf(nibbles)

        override fun find(id: PetId): Pet? = nibbles.takeIf { it.id == id }

        override fun adopt(id: PetId, by: String): Either<PetShopError, Pet> = NoSuchPet(id.value).left()
    }

    private val api = petshopApi(
        shop,
        health = { Healthy(ready = true, failing = emptyList()) },
        scrape = { "" },
        tally = { Tally(events = 0, duplicates = 0, bySpecies = emptyList()) },
    )

    @Test
    fun `every endpoint publishes what it published`() {
        golden.operations(api.spec())
    }

    @Test
    fun `a pet, and a refusal, look on the wire the way they did`() {
        val client = api.inMemory("petshop-golden")

        golden.exchange("get-pet", client, getPet, 1L)
        golden.exchange("adopt-missing-pet", client, adoptPet, 404L)
    }
}
