package petshop.domain

/** `seq` is the event's identity, not its position: a retried event can arrive after a later one. */
sealed interface ShopEvent {
    val seq: Long
}

data class PetArrived(override val seq: Long, val pet: Pet) : ShopEvent

data class PetAdopted(override val seq: Long, val pet: Pet, val by: String) : ShopEvent

/**
 * An adoption undone, from before the registry had to confirm one (ADR 0001). The shop writes none now, but the
 * topic's history and an outbox left unpublished across the upgrade still hold them, so they are read as they were.
 */
data class PetReturned(override val seq: Long, val pet: Pet) : ShopEvent
