package petshop.api

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.github.matthewjones372.pelican.test.shouldBeError
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.github.matthewjones372.pelican.test.shouldBuild
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import petshop.domain.AlreadyAdopted
import petshop.domain.NoSuchPet
import petshop.domain.NotChipped
import petshop.domain.NotRecorded
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.PetShopError
import petshop.domain.RegistryDown
import petshop.domain.Species

private class OnePet(private var pet: Pet) : PetShop {
    override fun all(): List<Pet> = listOf(pet)

    override fun find(id: PetId): Pet? = pet.takeIf { it.id == id }

    override fun adopt(id: PetId, by: String): Either<PetShopError, Pet> = when {
        pet.id != id -> NoSuchPet(id.value).left()
        pet.adopted -> AlreadyAdopted(id.value).left()
        else -> pet.copy(adopted = true).also { pet = it }.right()
    }
}

/**
 * Two promises, kept apart on purpose: what the shop's own code may call, and what its callers hold.
 * A rename breaks the first at compile time; only the second is allowed to notice a URL.
 */
class ContractSpec {

    private val nibbles = Pet(PetId(1), "Nibbles", Species.Tortoise)

    private val app = petshopApi(
        shop = OnePet(nibbles),
        health = { Healthy(ready = true, failing = emptyList()) },
        scrape = { "petshop_adoptions_total 1.0" },
        tally = { Tally(events = 0, duplicates = 0, bySpecies = emptyList()) },
    ).inMemory("petshop-contract")

    @Test
    fun `a test names the endpoint, not the URL`() {
        app.outcome(getPet, 1L).shouldBeOk() shouldBe nibbles.toDto()
    }

    @Test
    fun `and then pins the URL on purpose, because callers hold it`() {
        app.request(getPet, 1L) shouldBuild "GET /pets/1"
        app.request(adoptPet, 1L) shouldBuild "POST /pets/1/adoption"
        app.request(listPets, Unit) shouldBuild "GET /pets"
        app.request(health, Unit) shouldBuild "GET /health"
        app.request(metrics, Unit) shouldBuild "GET /metrics"
        app.request(stats, Unit) shouldBuild "GET /stats"
    }

    @Test
    fun `adopting twice answers the failure the endpoint declared`() {
        app.outcome(adoptPet, 1L).shouldBeOk() shouldBe nibbles.copy(adopted = true).toDto()

        app.outcome(adoptPet, 1L).shouldBeError() shouldBe ProblemDto.AlreadyAdopted(1, "Pet 1 is already adopted")
    }

    @Test
    fun `a missing pet answers the declared 404, with the domain's own message`() {
        app.outcome(getPet, 2L).shouldBeError() shouldBe ProblemDto.NoSuchPet(2, "No pet 2")
    }

    @Test
    fun `the DTO carries the id inside PetId, so the JSON id is a plain number`() {
        nibbles.toDto() shouldBe PetDto(1, "Nibbles", SpeciesDto.Tortoise, adopted = false)
    }

    @Test
    fun `the registry's refusals, and a sale the shop could not record, reach the caller as declared failures`() {
        listOf(
            NotChipped(1) to ProblemDto.NotChipped(1, NotChipped(1).message),
            RegistryDown(1) to ProblemDto.Unavailable(1, RegistryDown(1).message),
            NotRecorded(1) to ProblemDto.Unavailable(1, NotRecorded(1).message),
        ).forEach { (refusal, declared) ->
            val refusing = petshopApi(
                shop = object : PetShop by OnePet(nibbles) {
                    override fun adopt(id: PetId, by: String): Either<PetShopError, Pet> = refusal.left()
                },
                health = { Healthy(ready = true, failing = emptyList()) },
                scrape = { "" },
                tally = { Tally(events = 0, duplicates = 0, bySpecies = emptyList()) },
            ).inMemory("petshop-refusing-${refusal::class.simpleName}")

            refusing.outcome(adoptPet, 1L).shouldBeError() shouldBe declared
        }
    }

}
