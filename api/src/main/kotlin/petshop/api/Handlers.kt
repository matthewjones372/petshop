package petshop.api

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.health.Health
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.pekko.handledNow
import io.github.matthewjones372.pelican.pekko.handledOrFail
import petshop.domain.NoSuchPet
import petshop.domain.PetId
import petshop.domain.PetShop

/**
 * The endpoints answered. A handler names the declared failure it is producing, so returning one the
 * endpoint never declared does not compile.
 */
fun petshopApi(shop: PetShop, health: Health, scrape: () -> String, tally: () -> Tally) = api(
    // /health/live and /health/ready are pelican-health's, answered from the checks the service declares.
    endpoints = health.endpoints + listOf(
        metrics handledNow { scrape() },
        stats handledNow { tally() },
        listPets handledNow { shop.all().toDto() },
        getPet handledOrFail { id ->
            shop.find(PetId(id))?.let { pet -> ok(pet.toDto()) } ?: petMissing(NoSuchPet(id).toDto())
        },
        adoptPet handledOrFail { id ->
            shop.adopt(PetId(id), by = "the internet").fold(
                { failure ->
                    when (val problem = failure.toDto()) {
                        is ProblemDto.NoSuchPet -> petMissing(problem)
                        is ProblemDto.AlreadyAdopted -> petTaken(problem)
                        is ProblemDto.NotChipped -> petNotChipped(problem)
                        is ProblemDto.RegistryDown -> registryDown(problem)
                        is ProblemDto.NotRecorded -> notRecorded(problem)
                    }
                },
                { pet -> ok(pet.toDto()) },
            )
        },
    ),
    codecs = JacksonCodecs,
) {
    title = "Petshop"
    version = "1.0.0"
}
