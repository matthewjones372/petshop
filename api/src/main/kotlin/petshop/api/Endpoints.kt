package petshop.api

import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.or
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.text
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import petshop.domain.Species
import kotlin.time.Duration.Companion.seconds

val petId = pathParam<Long>("petId", description = "The pet's id")

val petMissing = errorJson<ProblemDto.NoSuchPet>(404, "No pet with that id")

// Both are a 409, so the tag tells a client which.
val petTaken = errorJson<ProblemDto.AlreadyAdopted>(409, "That pet has already been adopted").tagged("already_adopted")

val petBeingAdopted = errorJson<ProblemDto.BeingAdopted>(
    409,
    "That pet is being adopted; it may come back to the shelf",
)
    .tagged("being_adopted")

val petNotChipped = errorJson<ProblemDto.NotChipped>(422, "The registry has no chip for that pet")

// Both worth trying again, so both a 503. A status names one response unless its failures are tagged: the tag is
// written into the body as `kind`, and a client reads the failure back by it (Pelican spec 0063).
val registryDown = errorJson<ProblemDto.RegistryDown>(503, "The chip registry could not be reached; try again")
    .tagged("registry_down")

val notRecorded = errorJson<ProblemDto.NotRecorded>(503, "The adoption could not be recorded; try again")
    .tagged("not_recorded")

val listPets = endpoint {
    get("pets")
    tag("pets")
    summary = "Every pet in the shop"
    json<List<PetDto>>()
}

val getPet = endpoint(petId) {
    get("pets" / petId)
    tag("pets")
    summary = "One pet"
    json<PetDto>() orFail petMissing
}

val adoptionPending = json<PetDto>(202, description = "Recorded; the chip registry has yet to confirm it")

val adoptPet = endpoint(petId) {
    post("pets" / petId / "adoption")
    tag("pets")
    summary = "Take a pet home"
    (json<PetDto>() or adoptionPending).orFail(
        petMissing,
        petTaken,
        petBeingAdopted,
        petNotChipped,
        registryDown,
        notRecorded,
    )
}

/** `text`, not `json`: Prometheus cannot parse its exposition format wrapped in a JSON string. */
val metrics = endpoint {
    get("metrics")
    tag("shop")
    summary = "Every meter, in Prometheus' exposition format"
    text()
}

@Serializable
@SerialName("SpeciesTally")
data class SpeciesTally(val species: Species, val arrived: Int, val adopted: Int)

/** `duplicates` counts events the bus delivered again and the tally counted once. */
@Serializable
@SerialName("Tally")
data class Tally(val events: Int, val duplicates: Int, val bySpecies: List<SpeciesTally>)

val stats = endpoint {
    get("stats")
    tag("shop")
    summary = "What the shop's events add up to, as a consumer of them sees it"
    json<Tally>()
}

/** Live only: a reconnect starts from now, so `id` lets a caller drop what it has already shown, not resume. */
val events = endpoint {
    get("events")
    tag("shop")
    summary = "Arrivals, adoptions and returns, as the shop's events reach it"
    // keepAlive so a proxy does not close a quiet stream. No `retry`: Effect's SSE decoder ends the stream on one.
    sse<ShopEventDto>(eventName = "shop", id = { it.seq.toString() }, keepAlive = 15.seconds)
}
