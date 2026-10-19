# Petshop

A shop that keeps pets on a shelf and hands them to new keepers, with the chip registry recording who keeps each pet.

## Language

**Adoption**:
The sequence that hands a pet to a new keeper: the shop decides and records it, then the chip registry transfers the pet's chip.
_Avoid_: Purchase, sale, hand-over

**Return**:
The pet going back on the shelf because the chip registry refused its adoption, or never confirmed it. Nobody outside the shop heard that adoption begin.
_Avoid_: Rollback, cancellation

**Standing**:
Where a pet is in its life at the shop: on the shelf, being adopted (recorded, the registry not yet confirmed), or adopted by a named adopter.
_Avoid_: Status, state, availability

**Adopter**:
The person an adoption hands a pet to, as the shop records it.
_Avoid_: Buyer, customer

**Settling**:
Finding out from the chip registry whether an unconfirmed adoption happened, then recording it as adopted or as a return.
_Avoid_: Reconciling, sweeping, recovery

**Keeper**:
The person the chip registry records as responsible for a pet.
_Avoid_: Owner, adopter (when meaning the registry's record)
