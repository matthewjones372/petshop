package petshop.api

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.pekko.handledNow
import io.github.matthewjones372.pelican.pekko.handledOrFail
import petshop.domain.AlreadyAdopted
import petshop.domain.NoSuchPet
import petshop.domain.PetId
import petshop.domain.PetShop

/**
 * The endpoints answered. A handler names the declared failure it is producing, so returning one the
 * endpoint never declared does not compile.
 */
fun petshopApi(shop: PetShop) = api(
    endpoints = listOf(
        listPets handledNow { shop.all() },
        getPet handledOrFail { id ->
            shop.find(PetId(id))?.let { pet -> ok(pet) } ?: petMissing(NoSuchPet(id))
        },
        adoptPet handledOrFail { id ->
            shop.adopt(PetId(id), by = "the internet").fold(
                { failure ->
                    when (failure) {
                        is NoSuchPet -> petMissing(failure)
                        is AlreadyAdopted -> petTaken(failure)
                    }
                },
                { pet -> ok(pet) },
            )
        },
    ),
    codecs = JacksonCodecs,
) {
    title = "Petshop"
    version = "1.0.0"
}
