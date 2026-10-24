package petshop.domain

@JvmInline value class PetId(val value: Long)

enum class Species { Cat, Dog, Parrot, Tortoise }

/** Where a pet is in its life at the shop. The adopter exists only where an adoption does. */
sealed interface Standing

data object OnShelf : Standing

/** Recorded, and the chip registry has not yet confirmed the new keeper. */
data class Adopting(val by: String) : Standing

data class Adopted(val by: String) : Standing

data class Pet(val id: PetId, val name: String, val species: Species, val standing: Standing = OnShelf)

sealed interface PetShopError {
    val message: String
}

data class NoSuchPet(val id: Long, override val message: String = "No pet $id") : PetShopError

data class AlreadyAdopted(val id: Long, override val message: String = "Pet $id is already adopted") : PetShopError

/** Another adoption of this pet is waiting on the chip registry; it may yet come back to the shelf. */
data class BeingAdopted(val id: Long, override val message: String = "Pet $id is being adopted") : PetShopError

data class NotChipped(val id: Long, override val message: String = "Pet $id has no registered chip") : PetShopError

data class RegistryDown(val id: Long, override val message: String = "The chip registry could not be reached") :
    PetShopError

/** Recording failed before the registry was asked, so the pet is still on the shelf. */
data class NotRecorded(val id: Long, override val message: String = "The adoption of pet $id could not be recorded") :
    PetShopError

interface PetShop {
    fun all(): List<Pet>

    fun find(id: PetId): Pet?

    fun adopt(id: PetId, by: String): io.github.matthewjones372.lark.fail.Either<PetShopError, Pet>
}
