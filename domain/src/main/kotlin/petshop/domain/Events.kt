package petshop.domain

/**
 * Something that happened in the shop, numbered in the order the shop recorded it.
 *
 * The number is the event's identity rather than its position: a consumer told the same one twice
 * knows it by `seq`, and one that was retried can arrive after a later one.
 */
sealed interface ShopEvent {
    val seq: Long
}

data class PetArrived(override val seq: Long, val pet: Pet) : ShopEvent

data class PetAdopted(override val seq: Long, val pet: Pet, val by: String) : ShopEvent

/** An adoption undone after it was recorded, because the chip registry would not take the new keeper. */
data class PetReturned(override val seq: Long, val pet: Pet) : ShopEvent
