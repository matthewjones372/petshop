package petshop.api

import io.github.matthewjones372.kimney.into
import io.github.matthewjones372.kimney.transformInto
import petshop.domain.AlreadyAdopted
import petshop.domain.NoSuchPet
import petshop.domain.NotRecorded
import petshop.domain.Pet
import petshop.domain.PetShopError
import petshop.domain.RegistryDown

/**
 * What the wire holds, kept apart from the domain so a change to either is a compile error at the
 * crossing rather than a silent change to the JSON. kimney writes each crossing; none is by hand.
 */
enum class SpeciesDto { Cat, Dog, Parrot, Tortoise }

/** `id` is the `Long` inside `PetId`: kimney unwraps the value class, so the JSON is a plain number. */
data class PetDto(val id: Long, val name: String, val species: SpeciesDto, val adopted: Boolean)

/** The declared failures: a case per domain failure by name, except where the crossing renames one. */
sealed interface ProblemDto {
    val id: Long
    val message: String

    data class NoSuchPet(override val id: Long, override val message: String) : ProblemDto

    data class AlreadyAdopted(override val id: Long, override val message: String) : ProblemDto

    data class NotChipped(override val id: Long, override val message: String) : ProblemDto

    /**
     * An adoption that could not be finished just now, and is worth trying again: the chip registry could
     * not be reached, or the shop could not write the sale down. One case for both because a status names
     * exactly one response, and the [message] says which it was.
     */
    data class Unavailable(override val id: Long, override val message: String) : ProblemDto
}

fun Pet.toDto(): PetDto = transformInto()

fun List<Pet>.toDto(): List<PetDto> = transformInto()

/** A domain failure added without a case here, or a rename below, stops the build at this call. */
fun PetShopError.toDto(): ProblemDto = into<_, ProblemDto>()
    .withSealedCaseRenamed(RegistryDown::class, ProblemDto.Unavailable::class)
    .withSealedCaseRenamed(NotRecorded::class, ProblemDto.Unavailable::class)
    .transform()

fun NoSuchPet.toDto(): ProblemDto.NoSuchPet = transformInto()

fun AlreadyAdopted.toDto(): ProblemDto.AlreadyAdopted = transformInto()
