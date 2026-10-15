package petshop.api

import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.health.Health
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.jetty.handledOrFail
import io.github.matthewjones372.pelican.jetty.streamedNow
import io.github.matthewjones372.pelican.metrics.otel.openTelemetry
import io.github.matthewjones372.pelican.ok
import io.opentelemetry.api.OpenTelemetry
import petshop.domain.NoSuchPet
import petshop.domain.PetId
import petshop.domain.PetShop

/** [streams] runs each `/events` caller's feed. */
fun petshopApi(
    streams: StreamBackend,
    shop: PetShop,
    health: Health,
    scrape: () -> String,
    tally: () -> Tally,
    /** A fresh run per caller: each connection is a subscriber of its own. */
    watch: () -> Stream<Nothing, ShopEventDto>,
    telemetry: OpenTelemetry = OpenTelemetry.noop(),
) = api(
    endpoints = listOf(
        listPets handledNow { shop.all().toDto() },
        getPet handledOrFail { id ->
            shop.find(PetId(id))?.let { pet -> ok(pet.toDto()) } ?: petMissing(NoSuchPet(id).toDto())
        },
        adoptPet handledOrFail { id ->
            shop.adopt(PetId(id), by = "the internet").fold(
                { failure ->
                    when (val problem = failure.toDto()) {
                        is ProblemDto.NoSuchPet -> petMissing(problem)
                        is ProblemDto.AlreadyAdopted -> petTaken(problem)
                        is ProblemDto.NotChipped -> petNotChipped(problem)
                        is ProblemDto.RegistryDown -> registryDown(problem)
                        is ProblemDto.NotRecorded -> notRecorded(problem)
                    }
                },
                { pet -> ok(pet.toDto()) },
            )
        },
        metrics handledNow { scrape() },
        stats handledNow { tally() },
        events streamedNow { watch().pulledOn(streams) },
    ) + health.endpoints,
    codecs = ShopCodecs,
) {
    title = "Petshop"
    version = "1.0.0"
    filter(openTelemetry(telemetry, incomingHeaders = requestHeaders))
    description = """
        A small shop of tortoises, dogs, cats and parrots. An adoption records the new keeper with the chip
        registry before the pet leaves the shop, and a pet with no chip on record stays on the shelf.

        **pets** is the shop itself: what is on the shelf, and taking one home. **shop** is what the shop says about
        itself, its meters and what its events add up to. **health** is whether it can take traffic.
    """.trimIndent()
}
