package petshop.api

import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.left
import io.github.matthewjones372.lark.junit.Expectations
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.expect
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.empty
import io.github.matthewjones372.pelican.health.health
import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.test.golden.Golden
import io.github.matthewjones372.pelican.test.jetty.inMemory
import petshop.domain.NoSuchPet
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.PetShopError
import petshop.domain.Species

/** A change that breaks a caller fails; one that does not rewrites the golden file, so it shows in the diff. */
object GoldenSuite : Suite() {
    private val golden = Golden()

    private val nibbles = Pet(PetId(1), "Nibbles", Species.Tortoise)

    private val shop = object : PetShop {
        override fun all(): List<Pet> = listOf(nibbles)

        override fun find(id: PetId): Pet? = nibbles.takeIf { it.id == id }

        override fun adopt(id: PetId, by: String): Either<PetShopError, Pet> = NoSuchPet(id.value).left()
    }

    private val api = petshopApi(
        Forks(),
        shop,
        health = health { },
        scrape = { "" },
        tally = { Tally(events = 0, duplicates = 0, bySpecies = emptyList()) },
        watch = { Stream.empty() },
    )

    init {
        test("every endpoint publishes what it published") {
            matches { golden.operations(api.spec()) }
        }

        test("a pet, and a refusal, look on the wire the way they did") {
            val client = api.inMemory(Handlers.onVirtualThreads)

            matches { golden.exchange("get-pet", client, getPet, 1L) } and
                matches { golden.exchange("adopt-missing-pet", client, adoptPet, 404L) }
        }
    }

    // Golden reports a broken or unreviewed file by throwing an AssertionError; this keeps it as an expectation.
    private fun matches(check: () -> Unit): Expectations = try {
        check()
        expect(true)
    } catch (mismatch: AssertionError) {
        expect(false) { mismatch.message.orEmpty() }
    }
}
