package petshop.app

import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beTheSameInstanceAs
import kotlin.reflect.typeOf
import org.junit.jupiter.api.Test
import petshop.api.PetDto
import petshop.api.ShopCodecs
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.Species

class CatalogueBytesSpec {

    @Test
    fun `a response keeps its catalogue bytes after another catalogue is published`() {
        val shown = window(1, "Nibbles", Species.Tortoise)
        val codec = ShopCodecs.codec<List<PetDto>>(typeOf<List<PetDto>>())
        val selected = shown.listed()
        val bytes = requireNotNull(codec.encoded(selected))

        val barnaby = Pet(PetId(2), "Barnaby", Species.Dog)
        shown.show(mapOf(barnaby.id to barnaby))

        codec.encoded(selected) should beTheSameInstanceAs<ByteArray?>(bytes)
        codec.encodeToString(selected) shouldBe
            """[{"id":1,"name":"Nibbles","species":"Tortoise","adopted":false}]"""
    }

    @Test
    fun `a published catalogue reuses its bytes across codecs`() {
        val nibbles = window(1, "Nibbles", Species.Tortoise)
        val barnaby = window(2, "Barnaby", Species.Dog)
        val pets = typeOf<List<PetDto>>()
        val nibblesCodec = ShopCodecs.codec<List<PetDto>>(pets)
        val barnabyCodec = ShopCodecs.codec<List<PetDto>>(pets, "application/json")

        val bytes = requireNotNull(nibblesCodec.encoded(nibbles.listed()))
        barnabyCodec.encoded(nibbles.listed()) should beTheSameInstanceAs<ByteArray?>(bytes)
        nibblesCodec.encoded(nibbles.listed().toList()) shouldBe null
        requireNotNull(barnabyCodec.encoded(barnaby.listed())).decodeToString() shouldBe
            """[{"id":2,"name":"Barnaby","species":"Dog","adopted":false}]"""
    }

    @Test
    fun `a new catalogue is a new array`() {
        val nibbles = window(1, "Nibbles", Species.Tortoise)
        val codec = ShopCodecs.codec<List<PetDto>>(typeOf<List<PetDto>>())
        val published = requireNotNull(codec.encoded(nibbles.listed()))
        nibbles.show(mapOf(PetId(1) to Pet(PetId(1), "Nibbles", Species.Tortoise)))
        (requireNotNull(codec.encoded(nibbles.listed())) === published) shouldBe false
    }

    private fun window(id: Long, name: String, species: Species): ShopWindow {
        val pet = Pet(PetId(id), name, species)
        return ShopWindow().also { it.show(mapOf(pet.id to pet)) }
    }
}
