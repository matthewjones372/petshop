package petshop.api

import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import java.util.RandomAccess
import kotlin.reflect.typeOf

/** Keeps a response's encoding with its list after the shop publishes a newer catalogue. */
class Catalogue(pets: List<PetDto>) :
    AbstractList<PetDto>(),
    RandomAccess {

    private val pets = pets.toList()

    internal val json: String = catalogueJson.encodeToString(this.pets)
    internal val bytes: ByteArray = json.toByteArray(Charsets.UTF_8)

    override val size: Int get() = pets.size

    override fun get(index: Int): PetDto = pets[index]
}

private val catalogueJson = KotlinxCodecs.codec<List<PetDto>>(typeOf<List<PetDto>>())
