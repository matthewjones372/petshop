package petshop.api

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.left
import io.github.matthewjones372.lark.fail.right
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.forAll
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeInstanceOf
import io.github.matthewjones372.lark.resource
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
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
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

object ContractSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    private val nibbles = Pet(PetId(1), "Nibbles", Species.Tortoise)

    // No checks: the probes' paths are Pelican's whatever the app declares.
    private val probes = health { }

    // A function, not a property: OnePet remembers an adoption, so each test needs its own shop.
    private fun app() = petshopApi(
        streams = Forks(),
        shop = OnePet(nibbles),
        health = probes,
        scrape = { "petshop_adoptions_total 1.0" },
        tally = { Tally(events = 0, duplicates = 0, bySpecies = emptyList()) },
        watch = { Stream.empty() },
    ).inMemory(Handlers.onVirtualThreads)

    init {
        test("a test names the endpoint, not the URL") { _ ->
            app().outcome(getPet, 1L).shouldBeOk() shouldBe nibbles.toDto()
        }

        test("and then pins the URL on purpose, because callers hold it") { _ ->
            val app = app()

            // A request's toString is the line pelican-test's shouldBuild compares.
            (app.request(getPet, 1L).toString() shouldBe "GET /pets/1") and
                (app.request(adoptPet, 1L).toString() shouldBe "POST /pets/1/adoption") and
                (app.request(listPets, Unit).toString() shouldBe "GET /pets") and
                (app.request(probes.live, Unit).toString() shouldBe "GET /health/live") and
                (app.request(probes.ready, Unit).toString() shouldBe "GET /health/ready") and
                (app.request(metrics, Unit).toString() shouldBe "GET /metrics") and
                (app.request(stats, Unit).toString() shouldBe "GET /stats") and
                (app.request(events, Unit).toString() shouldBe "GET /events")
        }

        test("adopting twice answers the failure the endpoint declared") { _ ->
            val app = app()

            val first = app.outcome(adoptPet, 1L).shouldBeOk() shouldBe nibbles.copy(standing = Adopted("Ada")).toDto()
            val second = app.outcome(adoptPet, 1L).shouldBeError()

            first and (second shouldBe ProblemDto.AlreadyAdopted(1, "Pet 1 is already adopted"))
        }

        test("a missing pet answers the declared 404, with the domain's own message") { _ ->
            app().outcome(getPet, 2L).shouldBeError() shouldBe ProblemDto.NoSuchPet(2, "No pet 2")
        }

        test("the DTO carries the id inside PetId, so the JSON id is a plain number") { _ ->
            nibbles.toDto() shouldBe PetDto(1, "Nibbles", SpeciesDto.Tortoise, adopted = false)
        }

        test(
            "the registry's refusals, and a sale the shop could not record, reach the caller as declared failures",
        ) { _ ->
            listOf(
                NotChipped(1) to ProblemDto.NotChipped(1, NotChipped(1).message),
                RegistryDown(1) to ProblemDto.RegistryDown(1, RegistryDown(1).message),
                NotRecorded(1) to ProblemDto.NotRecorded(1, NotRecorded(1).message),
            ).forAll { (refusal, declared) ->
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

        test("a pet someone else is adopting answers its own declared 409") { _ ->
            val busy = shopAnswering(BeingAdopted(1).left())

            busy.outcome(adoptPet, 1L).shouldBeError() shouldBe ProblemDto.BeingAdopted(1, BeingAdopted(1).message)
        }

        test("an adoption the registry has not yet confirmed answers the declared 202, with the pet taken") { _ ->
            val pending = shopAnswering(nibbles.copy(standing = Adopting("Ada")).right())

            val answer = pending.outcome(adoptPet, 1L)

            answer.shouldBeInstanceOf<Outcome.Ok<*>> { ok ->
                (ok.declared shouldBe adoptionPending) and
                    (ok.value shouldBe nibbles.copy(standing = Adopting("Ada")).toDto())
            }
        }

        test("the events endpoint streams each event the feed hands it, then ends with the feed") { _ ->
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

        test("a request arriving with a traceparent is handled inside the caller's trace") { _ ->
            val callersTrace = "4bf92f3577b34da6a3ce929d0e0e4736"
            var handledIn: String? = null
            val traced = petshopApi(
                streams = Forks(),
                shop = object : PetShop by OnePet(nibbles) {
                    override fun all(): List<Pet> =
                        listOf(nibbles).also { handledIn = Span.current().spanContext.traceId }
                },
                health = probes,
                scrape = { "" },
                tally = { Tally(events = 0, duplicates = 0, bySpecies = emptyList()) },
                watch = { Stream.empty() },
                telemetry = OpenTelemetry.propagating(
                    ContextPropagators.create(W3CTraceContextPropagator.getInstance()),
                ),
            ).inMemory(Handlers.onVirtualThreads)

            val answered = traced.transport.send(
                traced.request(listPets, Unit).withHeader("traceparent", "00-$callersTrace-00f067aa0ba902b7-01"),
            ).status shouldBe 200

            answered and (handledIn shouldBe callersTrace)
        }
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
}
