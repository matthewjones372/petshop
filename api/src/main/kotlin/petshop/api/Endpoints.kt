package petshop.api

import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.text
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import petshop.domain.AlreadyAdopted
import petshop.domain.NoSuchPet
import petshop.domain.NotChipped
import petshop.domain.Pet
import petshop.domain.Species

/**
 * What the shop's HTTP contract is, as values. The server routes, the OpenAPI document and the typed
 * client all come from these; there is no second description of any of it.
 */
val petId = pathParam<Long>("petId", description = "The pet's id")

/** The two ways a request about a pet can fail, each with the status the document publishes. */
val petMissing = errorJson<NoSuchPet>(404, "No pet with that id")

val petTaken = errorJson<AlreadyAdopted>(409, "That pet has already been adopted")

/** The registry has no chip, which is the registry's answer and not the shop's. */
val petNotChipped = errorJson<NotChipped>(422, "The registry has no chip for that pet")

/**
 * An adoption that could not be finished just now, and is worth trying again: the chip registry could
 * not be reached (`RegistryDown`), or the shop could not write the sale down (`NotRecorded`). One type
 * for both because a status names exactly one response, and the [message] says which it was.
 */
data class Unavailable(val id: Long, val message: String)

val unavailable = errorJson<Unavailable>(503, "The adoption could not be finished just now; try again")

/** What the shop says when asked whether it can serve. */
data class Healthy(val ready: Boolean, val failing: List<String>)

val health = endpoint {
    get("health")
    summary = "Whether the shop can serve"
    json<Healthy>()
}

val listPets = endpoint {
    get("pets")
    summary = "Every pet in the shop"
    json<List<Pet>>()
}

val getPet = endpoint(petId) {
    get("pets" / petId)
    summary = "One pet"
    json<Pet>() orFail petMissing
}

val adoptPet = endpoint(petId) {
    post("pets" / petId / "adoption")
    summary = "Take a pet home"
    json<Pet>().orFail(petMissing, petTaken, petNotChipped, unavailable)
}

/**
 * What Prometheus scrapes, in the format it expects.
 *
 * `text` rather than `json`: the exposition format is line-oriented text, and a scraper handed a
 * JSON string would read a quoted blob it cannot parse.
 */
val metrics = endpoint {
    get("metrics")
    summary = "Every meter, in Prometheus' exposition format"
    text()
}

/** One species' share of what the shop's events have said so far. */
data class SpeciesTally(val species: Species, val arrived: Int, val adopted: Int)

/**
 * What the events have added up to, read from the bus rather than the shop. `duplicates` is how many
 * the bus delivered twice and the tally counted once.
 */
data class Tally(val events: Int, val duplicates: Int, val bySpecies: List<SpeciesTally>)

val stats = endpoint {
    get("stats")
    summary = "What the shop's events add up to, as a consumer of them sees it"
    json<Tally>()
}
