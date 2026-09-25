package petshop.app.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The shop's events as they are on the wire: Avro records whose schema avro4k derives from these classes.
// They are the shop's published contract, so they are their own types and not the domain's, and kimney
// maps between the two. A case is matched to the domain's by its name, so the names here are the domain's.

/** One event on the wire. The Avro record wraps the union, so a topic has one schema and not one per case. */
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

@Serializable
@SerialName("petshop.events.PetReturned")
data class PetReturned(override val seq: Long, val pet: Pet) : WireEvent

@Serializable
@SerialName("petshop.events.Pet")
data class Pet(val id: Long, val name: String, val species: Species, val adopted: Boolean)

@Serializable
@SerialName("petshop.events.Species")
enum class Species { Cat, Dog, Parrot, Tortoise }
