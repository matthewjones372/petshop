package petshop.api

import io.github.matthewjones372.kimney.transformInto
import petshop.domain.AlreadyAdopted
import petshop.domain.NoSuchPet
import petshop.domain.Pet
import petshop.domain.PetShopError

/**
 * What the wire holds, kept apart from the domain so a change to either is a compile error at the
 * crossing rather than a silent change to the JSON. kimney writes each crossing; none is by hand.
 */
enum class SpeciesDto { Cat, Dog, Parrot, Tortoise }

/** `id` is the `Long` inside `PetId`: kimney unwraps the value class, so the JSON is a plain number. */
data class PetDto(val id: Long, val name: String, val species: SpeciesDto, val adopted: Boolean)

/** The declared failures, one case per domain case, matched by name. */
sealed interface ProblemDto {
    val id: Long
    val message: String

    data class NoSuchPet(override val id: Long, override val message: String) : ProblemDto

    data class AlreadyAdopted(override val id: Long, override val message: String) : ProblemDto

    data class NotChipped(override val id: Long, override val message: String) : ProblemDto

    data class RegistryDown(override val id: Long, override val message: String) : ProblemDto
}

fun Pet.toDto(): PetDto = transformInto()

fun List<Pet>.toDto(): List<PetDto> = transformInto()

/** A domain failure added without a case here stops the build at this call. */
fun PetShopError.toDto(): ProblemDto = transformInto()

fun NoSuchPet.toDto(): ProblemDto.NoSuchPet = transformInto()

fun AlreadyAdopted.toDto(): ProblemDto.AlreadyAdopted = transformInto()
