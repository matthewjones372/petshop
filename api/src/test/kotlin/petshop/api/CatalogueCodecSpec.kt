package petshop.api

import arrow.core.Either
import arrow.core.left
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.empty
import io.github.matthewjones372.pelican.health.health
import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.test.jetty.inMemory
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.beTheSameInstanceAs
import kotlin.reflect.typeOf
import org.junit.jupiter.api.Test
import petshop.domain.NoSuchPet
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.PetShopError
import petshop.domain.Species

class CatalogueCodecSpec {

    private val nibbles = listOf(PetDto(1, "Nibbles", SpeciesDto.Tortoise, adopted = false))

    private val barnaby = listOf(PetDto(2, "Barnaby", SpeciesDto.Dog, adopted = false))

    private val shop = object : PetShop {
        override fun all(): List<Pet> = listOf(Pet(PetId(1), "Nibbles", Species.Tortoise))

        override fun find(id: PetId): Pet? = null

        override fun adopt(id: PetId, by: String): Either<PetShopError, Pet> = NoSuchPet(id.value).left()
    }

    @Test
    fun `each shop serves the json published with its own catalogue`() {
        val plantedNibbles = """[{"planted":"nibbles"}]"""
        val plantedBarnaby = """[{"planted":"barnaby"}]"""
        val pets = typeOf<List<PetDto>>()
        val nibblesCodecs = shopCodecs({ nibbles }, { plantedNibbles }, { plantedNibbles.toByteArray() })
        val barnabyCodecs = shopCodecs({ barnaby }, { plantedBarnaby }, { plantedBarnaby.toByteArray() })

        nibblesCodecs.codec<List<PetDto>>(pets).encodeToString(nibbles) shouldBe plantedNibbles
        barnabyCodecs.codec<List<PetDto>>(pets).encodeToString(barnaby) shouldBe plantedBarnaby
        nibblesCodecs.codec<List<PetDto>>(pets).encodeToString(nibbles.toList()) shouldNotBe plantedNibbles
        nibblesCodecs.codec<List<PetDto>>(pets).encodeToString(barnaby) shouldBe
            ShopCodecs.codec<List<PetDto>>(pets).encodeToString(barnaby)

        served({ nibbles }, { plantedNibbles }).response(listPets, Unit).body shouldBe plantedNibbles
        served({ barnaby }, { plantedBarnaby }).response(listPets, Unit).body shouldBe plantedBarnaby
    }

    @Test
    fun `each shop serves the bytes published with its own catalogue`() {
        val nibblesBytes = byteArrayOf(1, 2, 3, 4)
        val barnabyBytes = byteArrayOf(5, 6, 7, 8)
        val pets = typeOf<List<PetDto>>()
        val nibblesCodec = shopCodecs({ nibbles }, { "nibbles" }, { nibblesBytes }).codec<List<PetDto>>(pets)
        val barnabyCodec = shopCodecs({ barnaby }, { "barnaby" }, { barnabyBytes }).codec<List<PetDto>>(pets)

        nibblesCodec.encoded(nibbles) should beTheSameInstanceAs<ByteArray?>(nibblesBytes)
        nibblesCodec.encoded(nibbles.toList()) shouldBe null
        nibblesCodec.encoded(barnaby) shouldBe null
        barnabyCodec.encoded(barnaby) should beTheSameInstanceAs<ByteArray?>(barnabyBytes)
        barnabyCodec.encoded(nibbles) shouldBe null
    }

    private fun served(listed: () -> List<PetDto>, json: () -> String) = petshopApi(
        Forks(),
        shop,
        health { },
        scrape = { "" },
        tally = { Tally(events = 0, duplicates = 0, bySpecies = emptyList()) },
        watch = { Stream.empty() },
        listed = listed,
        codecs = shopCodecs(listed, json) { json().toByteArray() },
    ).inMemory(Handlers.onVirtualThreads)
}
