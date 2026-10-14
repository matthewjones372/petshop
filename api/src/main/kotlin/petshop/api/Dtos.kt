package petshop.api

import io.github.matthewjones372.kimney.into
import io.github.matthewjones372.kimney.transformInto
import petshop.domain.AlreadyAdopted
import petshop.domain.NoSuchPet
import petshop.domain.NotRecorded
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetReturned
import petshop.domain.PetShopError
import petshop.domain.RegistryDown
import petshop.domain.ShopEvent

// Kept apart from the domain so a change to either breaks the build here, not the JSON silently.

enum class SpeciesDto { Cat, Dog, Parrot, Tortoise }

/** kimney unwraps `PetId`, so `id` is a plain number in the JSON. */
data class PetDto(val id: Long, val name: String, val species: SpeciesDto, val adopted: Boolean)

sealed interface ProblemDto {
    val id: Long
    val message: String

    data class NoSuchPet(override val id: Long, override val message: String) : ProblemDto

    data class AlreadyAdopted(override val id: Long, override val message: String) : ProblemDto

    data class NotChipped(override val id: Long, override val message: String) : ProblemDto

    /** Registry down or sale not recorded: one case for both because a status names one response. */
    data class Unavailable(override val id: Long, override val message: String) : ProblemDto
}

enum class ShopEventKind { Arrived, Adopted, Returned }

/** One shape for every event, so a caller reads `kind` rather than telling subtypes apart by their fields. */
data class ShopEventDto(val seq: Long, val kind: ShopEventKind, val pet: PetDto)

fun Pet.toDto(): PetDto = transformInto()

fun List<Pet>.toDto(): List<PetDto> = transformInto()

fun PetShopError.toDto(): ProblemDto = into<_, ProblemDto>()
    .withSealedCaseRenamed(RegistryDown::class, ProblemDto.Unavailable::class)
    .withSealedCaseRenamed(NotRecorded::class, ProblemDto.Unavailable::class)
    .transform()

fun NoSuchPet.toDto(): ProblemDto.NoSuchPet = transformInto()

fun AlreadyAdopted.toDto(): ProblemDto.AlreadyAdopted = transformInto()

fun ShopEvent.toDto(): ShopEventDto = when (this) {
    is PetArrived -> ShopEventDto(seq, ShopEventKind.Arrived, pet.toDto())
    is PetAdopted -> ShopEventDto(seq, ShopEventKind.Adopted, pet.toDto())
    is PetReturned -> ShopEventDto(seq, ShopEventKind.Returned, pet.toDto())
}
