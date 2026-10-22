package petshop.api

import io.github.matthewjones372.kimney.into
import io.github.matthewjones372.kimney.transformInto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import petshop.domain.AlreadyAdopted
import petshop.domain.NoSuchPet
import petshop.domain.OnShelf
import petshop.domain.Pet
import petshop.domain.PetAdopted
import petshop.domain.PetArrived
import petshop.domain.PetReturned
import petshop.domain.PetShopError
import petshop.domain.ShopEvent

// Kept apart from the domain so a change to either breaks the build here, not the JSON silently. Each is serial-named
// by its simple name, which is what the OpenAPI document's components are called.

@Serializable
@SerialName("SpeciesDto")
enum class SpeciesDto { Cat, Dog, Parrot, Tortoise }

/** kimney unwraps `PetId`, so `id` is a plain number in the JSON. */
@Serializable
@SerialName("PetDto")
data class PetDto(val id: Long, val name: String, val species: SpeciesDto, val adopted: Boolean)

sealed interface ProblemDto {
    val id: Long
    val message: String

    @Serializable
    @SerialName("NoSuchPet")
    data class NoSuchPet(override val id: Long, override val message: String) : ProblemDto

    @Serializable
    @SerialName("AlreadyAdopted")
    data class AlreadyAdopted(override val id: Long, override val message: String) : ProblemDto

    @Serializable
    @SerialName("BeingAdopted")
    data class BeingAdopted(override val id: Long, override val message: String) : ProblemDto

    @Serializable
    @SerialName("NotChipped")
    data class NotChipped(override val id: Long, override val message: String) : ProblemDto

    @Serializable
    @SerialName("RegistryDown")
    data class RegistryDown(override val id: Long, override val message: String) : ProblemDto

    /** The pet is still for sale. */
    @Serializable
    @SerialName("NotRecorded")
    data class NotRecorded(override val id: Long, override val message: String) : ProblemDto
}

@Serializable
@SerialName("ShopEventKind")
enum class ShopEventKind { Arrived, Adopted }

/** One shape for every event, so a caller reads `kind` rather than telling subtypes apart by their fields. */
@Serializable
@SerialName("ShopEventDto")
data class ShopEventDto(val seq: Long, val kind: ShopEventKind, val pet: PetDto, val traceparent: String? = null)

/** A pet being adopted reads as taken: nobody browsing needs to know a transfer is in flight. */
fun Pet.toDto(): PetDto = into<_, PetDto>().withFieldComputed(PetDto::adopted) { it.standing !is OnShelf }.transform()

fun List<Pet>.toDto(): List<PetDto> = map { it.toDto() }

/** A domain failure added without a case of the same name here stops the build at this call. */
fun PetShopError.toDto(): ProblemDto = transformInto()

fun NoSuchPet.toDto(): ProblemDto.NoSuchPet = transformInto()

fun AlreadyAdopted.toDto(): ProblemDto.AlreadyAdopted = transformInto()

/** Null for a return: the feed shows what happens now, and a return only comes from history before ADR 0001. */
fun ShopEvent.toDto(): ShopEventDto? = when (this) {
    is PetArrived -> ShopEventDto(seq, ShopEventKind.Arrived, pet.toDto())
    is PetAdopted -> ShopEventDto(seq, ShopEventKind.Adopted, pet.toDto())
    is PetReturned -> null
}
