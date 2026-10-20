package petshop.app.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The published Avro contract. kimney maps cases to the domain's by name, so names must match the domain's.

/** Wraps the union so a topic has one schema, not one per case. */
@Serializable
@SerialName("petshop.events.ShopEventRecord")
data class ShopEventRecord(val event: WireEvent)

@Serializable
sealed interface WireEvent {
    val seq: Long
}

@Serializable
@SerialName("petshop.events.PetArrived")
data class PetArrived(override val seq: Long, val pet: Pet) : WireEvent

@Serializable
@SerialName("petshop.events.PetAdopted")
data class PetAdopted(override val seq: Long, val pet: Pet, val by: String) : WireEvent

// Kept though the shop writes none: dropping a branch of the union leaves records already on the topic unreadable.
@Serializable
@SerialName("petshop.events.PetReturned")
data class PetReturned(override val seq: Long, val pet: Pet) : WireEvent

@Serializable
@SerialName("petshop.events.Pet")
data class Pet(val id: Long, val name: String, val species: Species, val adopted: Boolean)

@Serializable
@SerialName("petshop.events.Species")
enum class Species { Cat, Dog, Parrot, Tortoise }
