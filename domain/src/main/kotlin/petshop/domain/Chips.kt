package petshop.domain

import arrow.core.Either

/** A microchip as the registry records it: the number on the chip and who keeps the pet. */
data class Chip(val number: String, val keeper: String)

/** The two answers from the registry that are not a chip. */
sealed interface RegistryError

data object NotRegistered : RegistryError

data class Unreachable(val reason: String) : RegistryError

/**
 * The national chip registry, which is somebody else's service. The shop names this and nothing
 * about HTTP, which is what lets a test swap the whole thing for a fake — or keep it and swap the
 * server it talks to.
 */
interface ChipRegistry {
    fun lookup(id: PetId): Either<RegistryError, Chip>

    fun transfer(chip: Chip, to: String): Either<RegistryError, Chip>
}
