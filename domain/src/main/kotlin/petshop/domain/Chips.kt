package petshop.domain

import io.github.matthewjones372.surefold.fail.Either

data class Chip(val number: String, val keeper: String)

sealed interface RegistryError

data object NotRegistered : RegistryError

data class Unreachable(val reason: String) : RegistryError

interface ChipRegistry {
    fun lookup(id: PetId): Either<RegistryError, Chip>

    fun transfer(chip: Chip, to: String): Either<RegistryError, Chip>
}
