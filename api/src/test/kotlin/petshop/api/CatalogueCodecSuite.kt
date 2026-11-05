package petshop.api

import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeSameInstanceAs
import dev.surefold.stream.Forks
import dev.surefold.stream.Stream
import dev.surefold.stream.empty
import io.github.matthewjones372.pelican.health.health
import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.test.jetty.inMemory
import petshop.domain.NoSuchPet
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.PetShopError
import petshop.domain.Species
import kotlin.reflect.typeOf

object CatalogueCodecSuite : Suite() {
    private val nibbles = listOf(PetDto(1, "Nibbles", SpeciesDto.Tortoise, adopted = false))

    private val barnaby = listOf(PetDto(2, "Barnaby", SpeciesDto.Dog, adopted = false))

    private val shop = object : PetShop {
        override fun all(): List<Pet> = listOf(Pet(PetId(1), "Nibbles", Species.Tortoise))

        override fun find(id: PetId): Pet? = null

        override fun adopt(id: PetId, by: String): Either<PetShopError, Pet> = NoSuchPet(id.value).left()
    }

    init {
        test("each shop serves the json published with its own catalogue") {
            val nibblesJson = """[{"id":1,"name":"Nibbles","species":"Tortoise","adopted":false}]"""
            val barnabyJson = """[{"id":2,"name":"Barnaby","species":"Dog","adopted":false}]"""
            val one = Catalogue(nibbles)
            val other = Catalogue(barnaby)
            val codec = ShopCodecs.codec<List<PetDto>>(typeOf<List<PetDto>>())

            codec.encodeToString(one) shouldBe nibblesJson
            codec.encodeToString(other) shouldBe barnabyJson
            codec.encodeToString(one.toList()) shouldBe nibblesJson
            codec.decodeFromString(nibblesJson) shouldBe nibbles
            codec.decodeFrom(barnabyJson.toByteArray()) shouldBe barnaby
            served { one }.response(listPets, Unit).body shouldBe nibblesJson
            served { other }.response(listPets, Unit).body shouldBe barnabyJson
        }

        test("each catalogue reuses its own bytes") {
            val one = Catalogue(nibbles)
            val other = Catalogue(barnaby)
            val pets = typeOf<List<PetDto>>()
            val codec = ShopCodecs.codec<List<PetDto>>(pets)
            val typedCodec = ShopCodecs.codec<List<PetDto>>(pets, "application/json")
            val bytes = requireNotNull(codec.encoded(one))

            typedCodec.encoded(one) shouldBeSameInstanceAs bytes
            bytes.decodeToString() shouldBe """[{"id":1,"name":"Nibbles","species":"Tortoise","adopted":false}]"""
            codec.encoded(one.toList()) shouldBe null
            (requireNotNull(codec.encoded(other)) === bytes) shouldBe false
        }

        test("a catalogue stays consistent when the list it was built from changes") {
            val source = mutableListOf(nibbles.single(), barnaby.single())
            val catalogue = Catalogue(source)
            val codec = ShopCodecs.codec<List<PetDto>>(typeOf<List<PetDto>>())
            val bytes = requireNotNull(codec.encoded(catalogue))

            source.clear()

            catalogue shouldBe listOf(nibbles.single(), barnaby.single())
            codec.decodeFrom(bytes) shouldBe catalogue
        }
    }

    private fun served(listed: () -> List<PetDto>) = petshopApi(
        Forks(),
        shop,
        health { },
        scrape = { "" },
        tally = { Tally(events = 0, duplicates = 0, bySpecies = emptyList()) },
        watch = { Stream.empty() },
        listed = listed,
    ).inMemory(Handlers.onVirtualThreads)
}
