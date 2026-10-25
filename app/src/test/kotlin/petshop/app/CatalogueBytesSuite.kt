package petshop.app

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeSameInstanceAs
import io.github.matthewjones372.lark.resource
import petshop.api.PetDto
import petshop.api.ShopCodecs
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.Species
import kotlin.reflect.typeOf

object CatalogueBytesSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("a response keeps its catalogue bytes after another catalogue is published") { _ ->
            val shown = window(1, "Nibbles", Species.Tortoise)
            val codec = ShopCodecs.codec<List<PetDto>>(typeOf<List<PetDto>>())
            val selected = shown.listed()
            val bytes = requireNotNull(codec.encoded(selected))

            val barnaby = Pet(PetId(2), "Barnaby", Species.Dog)
            shown.show(mapOf(barnaby.id to barnaby))

            (codec.encoded(selected) shouldBeSameInstanceAs bytes) and
                (
                    codec.encodeToString(selected) shouldBe
                        """[{"id":1,"name":"Nibbles","species":"Tortoise","adopted":false}]"""
                    )
        }

        test("a published catalogue reuses its bytes across codecs") { _ ->
            val nibbles = window(1, "Nibbles", Species.Tortoise)
            val barnaby = window(2, "Barnaby", Species.Dog)
            val pets = typeOf<List<PetDto>>()
            val nibblesCodec = ShopCodecs.codec<List<PetDto>>(pets)
            val barnabyCodec = ShopCodecs.codec<List<PetDto>>(pets, "application/json")

            val bytes = requireNotNull(nibblesCodec.encoded(nibbles.listed()))
            val shared = barnabyCodec.encoded(nibbles.listed()) shouldBeSameInstanceAs bytes
            val copied = nibblesCodec.encoded(nibbles.listed().toList()) shouldBe null
            shared and copied and
                (
                    requireNotNull(barnabyCodec.encoded(barnaby.listed())).decodeToString() shouldBe
                        """[{"id":2,"name":"Barnaby","species":"Dog","adopted":false}]"""
                    )
        }

        test("a new catalogue is a new array") { _ ->
            val nibbles = window(1, "Nibbles", Species.Tortoise)
            val codec = ShopCodecs.codec<List<PetDto>>(typeOf<List<PetDto>>())
            val published = requireNotNull(codec.encoded(nibbles.listed()))
            nibbles.show(mapOf(PetId(1) to Pet(PetId(1), "Nibbles", Species.Tortoise)))
            (requireNotNull(codec.encoded(nibbles.listed())) === published) shouldBe false
        }
    }

    private fun window(id: Long, name: String, species: Species): ShopWindow {
        val pet = Pet(PetId(id), name, species)
        return ShopWindow().also { it.show(mapOf(pet.id to pet)) }
    }
}
