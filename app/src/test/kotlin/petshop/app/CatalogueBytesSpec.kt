package petshop.app

import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beTheSameInstanceAs
import kotlin.reflect.typeOf
import org.junit.jupiter.api.Test
import petshop.api.PetDto
import petshop.api.shopCodecs
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.Species

class CatalogueBytesSpec {

    @Test
    fun `a published catalogue keeps the byte array show stored`() {
        val nibbles = window(1, "Nibbles", Species.Tortoise)
        val barnaby = window(2, "Barnaby", Species.Dog)
        val pets = typeOf<List<PetDto>>()
        val nibblesCodec = shopCodecs(nibbles::listed, nibbles::json, nibbles::bytes).codec<List<PetDto>>(pets)
        val barnabyCodec = shopCodecs(barnaby::listed, barnaby::json, barnaby::bytes).codec<List<PetDto>>(pets)

        nibblesCodec.encoded(nibbles.listed()) should beTheSameInstanceAs<ByteArray?>(nibbles.bytes())
        nibblesCodec.encoded(nibbles.listed().toList()) shouldBe null
        nibblesCodec.encoded(barnaby.listed()) shouldBe null
        barnabyCodec.encoded(barnaby.listed()) should beTheSameInstanceAs<ByteArray?>(barnaby.bytes())
    }

    @Test
    fun `a new catalogue is a new array`() {
        val nibbles = window(1, "Nibbles", Species.Tortoise)
        val published = nibbles.bytes()
        nibbles.show(mapOf(PetId(1) to Pet(PetId(1), "Nibbles", Species.Tortoise)))
        (nibbles.bytes() === published) shouldBe false
    }

    private fun window(id: Long, name: String, species: Species): ShopWindow {
        val pet = Pet(PetId(id), name, species)
        return ShopWindow().also { it.show(mapOf(pet.id to pet)) }
    }
}
