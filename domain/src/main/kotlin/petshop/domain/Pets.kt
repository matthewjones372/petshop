package petshop.domain

@JvmInline value class PetId(val value: Long)

enum class Species { Cat, Dog, Parrot, Tortoise }

data class Pet(val id: PetId, val name: String, val species: Species, val adopted: Boolean = false)

data class Adoption(val pet: PetId, val by: String)

sealed interface PetShopError {
    val message: String
}

data class NoSuchPet(val id: Long, override val message: String = "No pet $id") : PetShopError

data class AlreadyAdopted(val id: Long, override val message: String = "Pet $id is already adopted") :
    PetShopError

data class NotChipped(val id: Long, override val message: String = "Pet $id has no registered chip") :
    PetShopError

data class RegistryDown(val id: Long, override val message: String = "The chip registry could not be reached") :
    PetShopError

/** Recording failed before the registry was asked, so the pet is still on the shelf. */
data class NotRecorded(val id: Long, override val message: String = "The adoption of pet $id could not be recorded") :
    PetShopError

interface PetShop {
    fun all(): List<Pet>

    fun find(id: PetId): Pet?

    fun adopt(id: PetId, by: String): arrow.core.Either<PetShopError, Pet>
}
