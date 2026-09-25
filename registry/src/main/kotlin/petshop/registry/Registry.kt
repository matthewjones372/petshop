package petshop.registry

import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam

/*
 * The national chip registry's HTTP contract, as values.
 *
 * The registry is somebody else's service. This file is the shop's reading of what the registry
 * publishes, and two things are built from it: the client in `src/main/generated` (generated,
 * committed, and checked on every build) and the WireMock stubs the tests answer with.
 */

val petId = pathParam<Long>("petId", description = "The id the shop registered the pet under")

val chipNumber = pathParam<String>("chipNumber", description = "The number printed on the chip")

val newKeeper = jsonBody<NewKeeper>(description = "Who keeps the pet from now on")

/** A chip as the registry records it. */
data class ChipRecord(val number: String, val keeper: String)

data class NewKeeper(val keeper: String)

/** What the registry answers a request it cannot serve with. */
data class Problem(val message: String)

val noSuchChip = errorJson<Problem>(404, "The registry has no chip under that id")

val lookupChip = endpoint(petId) {
    get("chips" / petId)
    summary = "The chip a pet was registered with"
    operationId = "lookupChip"
    json<ChipRecord>() orFail noSuchChip
}

val recordKeeper = endpoint(chipNumber, newKeeper) {
    post("chips" / chipNumber / "keeper")
    summary = "Record who keeps the pet the chip is in"
    operationId = "recordKeeper"
    json<ChipRecord>() orFail noSuchChip
}

/** What the generated client is read from. */
fun registrySpec(): ApiSpec = apiSpec(listOf(lookupChip, recordKeeper), schemas = JacksonCodecs) {
    title = "Registry"
    version = "1.0.0"
    description = "The national chip registry, as the petshop calls it."
    servers = listOf("http://127.0.0.1:8089")
}
