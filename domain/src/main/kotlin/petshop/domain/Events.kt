package petshop.domain

/** `seq` is the event's identity, not its position: a retried event can arrive after a later one. */
sealed interface ShopEvent {
    val seq: Long
}

data class PetArrived(override val seq: Long, val pet: Pet) : ShopEvent

data class PetAdopted(override val seq: Long, val pet: Pet, val by: String) : ShopEvent
