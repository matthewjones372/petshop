package petshop.domain

@JvmInline value class PetId(val value: Long)

enum class Species { Cat, Dog, Parrot, Tortoise }

data class Pet(val id: PetId, val name: String, val species: Species, val adopted: Boolean = false)

/** Somebody taking a pet home, which is the only thing in here that can fail. */
data class Adoption(val pet: PetId, val by: String)

sealed interface PetShopError {
    val message: String
}

data class NoSuchPet(val id: Long, override val message: String = "No pet $id") : PetShopError

data class AlreadyAdopted(val id: Long, override val message: String = "Pet $id is already adopted") :
    PetShopError

/** The registry has no chip for the pet, and the shop does not hand over a pet nobody could trace. */
data class NotChipped(val id: Long, override val message: String = "Pet $id has no registered chip") :
    PetShopError

/** The registry could not be asked, so the pet stays in the shop until it can. */
data class RegistryDown(val id: Long, override val message: String = "The chip registry could not be reached") :
    PetShopError

/**
 * The shop could not write the adoption down, so it did not happen: the pet is still on the shelf, and
 * the registry was never asked.
 */
data class NotRecorded(val id: Long, override val message: String = "The adoption of pet $id could not be recorded") :
    PetShopError

/** What the shop can do. The HTTP layer names this and nothing about how it is stored. */
interface PetShop {
    fun all(): List<Pet>

    fun find(id: PetId): Pet?

    fun adopt(id: PetId, by: String): arrow.core.Either<PetShopError, Pet>
}
