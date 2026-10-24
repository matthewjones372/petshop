package petshop.api

import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.left
import io.github.matthewjones372.lark.fail.right
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.empty
import io.github.matthewjones372.lark.stream.of
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.health.health
import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.test.jetty.inMemory
import io.github.matthewjones372.pelican.test.shouldBeError
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.github.matthewjones372.pelican.test.shouldBuild
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import org.junit.jupiter.api.Test
import petshop.domain.Adopted
import petshop.domain.Adopting
import petshop.domain.AlreadyAdopted
import petshop.domain.BeingAdopted
import petshop.domain.NoSuchPet
import petshop.domain.NotChipped
import petshop.domain.NotRecorded
import petshop.domain.OnShelf
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.PetShopError
import petshop.domain.RegistryDown
import petshop.domain.Species

private class OnePet(private var pet: Pet) : PetShop {
    override fun all(): List<Pet> = listOf(pet)

    override fun find(id: PetId): Pet? = pet.takeIf { it.id == id }

    override fun adopt(id: PetId, by: String): Either<PetShopError, Pet> = when {
        pet.id != id -> NoSuchPet(id.value).left()
        pet.standing != OnShelf -> AlreadyAdopted(id.value).left()
        else -> pet.copy(standing = Adopted(by)).also { pet = it }.right()
    }
}

class ContractSpec {

    private val nibbles = Pet(PetId(1), "Nibbles", Species.Tortoise)

    // No checks: the probes' paths are Pelican's whatever the app declares.
    private val probes = health { }

    private val app = petshopApi(
        streams = Forks(),
        shop = OnePet(nibbles),
        health = probes,
        scrape = { "petshop_adoptions_total 1.0" },
        tally = { Tally(events = 0, duplicates = 0, bySpecies = emptyList()) },
        watch = { Stream.empty() },
    ).inMemory(Handlers.onVirtualThreads)

    @Test
    fun `a test names the endpoint, not the URL`() {
        app.outcome(getPet, 1L).shouldBeOk() shouldBe nibbles.toDto()
    }

    @Test
    fun `and then pins the URL on purpose, because callers hold it`() {
        app.request(getPet, 1L) shouldBuild "GET /pets/1"
        app.request(adoptPet, 1L) shouldBuild "POST /pets/1/adoption"
        app.request(listPets, Unit) shouldBuild "GET /pets"
        app.request(probes.live, Unit) shouldBuild "GET /health/live"
        app.request(probes.ready, Unit) shouldBuild "GET /health/ready"
        app.request(metrics, Unit) shouldBuild "GET /metrics"
        app.request(stats, Unit) shouldBuild "GET /stats"
        app.request(events, Unit) shouldBuild "GET /events"
    }

    @Test
    fun `adopting twice answers the failure the endpoint declared`() {
        app.outcome(adoptPet, 1L).shouldBeOk() shouldBe nibbles.copy(standing = Adopted("Ada")).toDto()

        app.outcome(adoptPet, 1L).shouldBeError() shouldBe ProblemDto.AlreadyAdopted(1, "Pet 1 is already adopted")
    }

    @Test
    fun `a missing pet answers the declared 404, with the domain's own message`() {
        app.outcome(getPet, 2L).shouldBeError() shouldBe ProblemDto.NoSuchPet(2, "No pet 2")
    }

    @Test
    fun `the DTO carries the id inside PetId, so the JSON id is a plain number`() {
        nibbles.toDto() shouldBe PetDto(1, "Nibbles", SpeciesDto.Tortoise, adopted = false)
    }

    @Test
    fun `the registry's refusals, and a sale the shop could not record, reach the caller as declared failures`() {
        listOf(
            NotChipped(1) to ProblemDto.NotChipped(1, NotChipped(1).message),
            RegistryDown(1) to ProblemDto.RegistryDown(1, RegistryDown(1).message),
            NotRecorded(1) to ProblemDto.NotRecorded(1, NotRecorded(1).message),
        ).forEach { (refusal, declared) ->
            val refusing = petshopApi(
                streams = Forks(),
                shop = object : PetShop by OnePet(nibbles) {
                    override fun adopt(id: PetId, by: String): Either<PetShopError, Pet> = refusal.left()
                },
                health = health { },
                scrape = { "" },
                tally = { Tally(events = 0, duplicates = 0, bySpecies = emptyList()) },
                watch = { Stream.empty() },
            ).inMemory(Handlers.onVirtualThreads)

            refusing.outcome(adoptPet, 1L).shouldBeError() shouldBe declared
        }
    }

    @Test
    fun `a pet someone else is adopting answers its own declared 409`() {
        val busy = shopAnswering(BeingAdopted(1).left())

        busy.outcome(adoptPet, 1L).shouldBeError() shouldBe ProblemDto.BeingAdopted(1, BeingAdopted(1).message)
    }

    @Test
    fun `an adoption the registry has not yet confirmed answers the declared 202, with the pet taken`() {
        val pending = shopAnswering(nibbles.copy(standing = Adopting("Ada")).right())

        val answer = pending.outcome(adoptPet, 1L)

        (answer as Outcome.Ok).declared shouldBe adoptionPending
        answer.value shouldBe nibbles.copy(standing = Adopting("Ada")).toDto()
    }

    private fun shopAnswering(answer: Either<PetShopError, Pet>) = petshopApi(
        streams = Forks(),
        shop = object : PetShop {
            override fun all(): List<Pet> = listOf(nibbles)

            override fun find(id: PetId): Pet? = nibbles.takeIf { it.id == id }

            override fun adopt(id: PetId, by: String): Either<PetShopError, Pet> = answer
        },
        health = health { },
        scrape = { "" },
        tally = { Tally(events = 0, duplicates = 0, bySpecies = emptyList()) },
        watch = { Stream.empty() },
    ).inMemory(Handlers.onVirtualThreads)

    @Test
    fun `the events endpoint streams each event the feed hands it, then ends with the feed`() {
        val arrived = ShopEventDto(1, ShopEventKind.Arrived, nibbles.toDto())
        val adopted = ShopEventDto(2, ShopEventKind.Adopted, nibbles.copy(standing = Adopted("Ada")).toDto())
        val watched = petshopApi(
            streams = Forks(),
            shop = OnePet(nibbles),
            health = probes,
            scrape = { "" },
            tally = { Tally(events = 0, duplicates = 0, bySpecies = emptyList()) },
            watch = { Stream.of(arrived, adopted) },
        ).inMemory(Handlers.onVirtualThreads)

        watched.collect(events, Unit) shouldBe listOf(arrived, adopted)
    }

    @Test
    fun `a request arriving with a traceparent is handled inside the caller's trace`() {
        val callersTrace = "4bf92f3577b34da6a3ce929d0e0e4736"
        var handledIn: String? = null
        val traced = petshopApi(
            streams = Forks(),
            shop = object : PetShop by OnePet(nibbles) {
                override fun all(): List<Pet> = listOf(nibbles).also { handledIn = Span.current().spanContext.traceId }
            },
            health = probes,
            scrape = { "" },
            tally = { Tally(events = 0, duplicates = 0, bySpecies = emptyList()) },
            watch = { Stream.empty() },
            telemetry = OpenTelemetry.propagating(ContextPropagators.create(W3CTraceContextPropagator.getInstance())),
        ).inMemory(Handlers.onVirtualThreads)

        traced.transport.send(
            traced.request(listPets, Unit).withHeader("traceparent", "00-$callersTrace-00f067aa0ba902b7-01"),
        ).status shouldBe 200

        handledIn shouldBe callersTrace
    }
}
