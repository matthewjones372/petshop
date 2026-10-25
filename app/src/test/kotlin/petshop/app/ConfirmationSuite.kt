package petshop.app

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeLeft
import io.github.matthewjones372.lark.junit.shouldBeRight
import io.github.matthewjones372.lark.junit.shouldNotBeNull
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.junit.story
import io.github.matthewjones372.lark.resource
import petshop.domain.Adopted
import petshop.domain.Adopting
import petshop.domain.BeingAdopted
import petshop.domain.Chip
import petshop.domain.ChipRegistry
import petshop.domain.NotChipped
import petshop.domain.OnShelf
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.PetShopError
import petshop.domain.RegistryDown
import petshop.domain.RegistryError
import petshop.domain.ShopEvent
import java.sql.SQLTransientConnectionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Holds every transfer until the test lets it through, so a test can act while an adoption is in flight. */
private class HeldRegistry : ChipRegistry {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun lookup(id: PetId): Either<RegistryError, Chip> = FakeRegistry().lookup(id)

    override fun transfer(chip: Chip, to: String): Either<RegistryError, Chip> {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "the transfer was never released" }
        return FakeRegistry().transfer(chip, to)
    }
}

object ConfirmationSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("someone asking for a pet while its transfer is in flight is told it is being adopted") { _ ->
            story {
                val registry = Given("a registry that holds Ada's transfer") { HeldRegistry() }
                val (bea, ada) = When("Bea asks for Nibbles while Ada's transfer is held, and then it goes through") {
                    testApp(shopWith(registry)) { shop: PetShop ->
                        var adas: Either<PetShopError, Pet>? = null
                        val adopting = Thread { adas = shop.adopt(PetId(1), by = "Ada") }.apply { start() }
                        check(registry.entered.await(5, TimeUnit.SECONDS)) { "Ada's transfer never started" }
                        val beas = shop.adopt(PetId(1), by = "Bea")
                        registry.release.countDown()
                        adopting.join(5_000)
                        beas to adas
                    }
                }
                Then("Bea is told Nibbles is being adopted") { bea shouldBeLeft BeingAdopted(1) } and
                    And("Ada has her, confirmed") {
                        ada.shouldNotBeNull { it.shouldBeRight { pet -> pet.standing shouldBe Adopted("Ada") } }
                    }
            }
        }

        test("a transfer whose answer was lost is confirmed when the registry says the adopter keeps the pet") { _ ->
            story {
                val registry = Given("a registry that records Ada but loses its answer") {
                    ScriptedRegistry(transfers = { ScriptedRegistry.Transfer.RecordsButLosesTheAnswer })
                }
                val answer = When(
                    "Ada adopts Nibbles",
                ) { testApp(shopWith(registry)) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") } }
                Then(
                    "reading the keeper back confirms her adoption",
                ) { answer.shouldBeRight { it.standing shouldBe Adopted("Ada") } }
            }
        }

        test("a transfer that never landed is refused once the registry says the shop still keeps the pet") { _ ->
            story {
                val registry = Given("a registry that loses Ada's transfer before recording it") {
                    ScriptedRegistry(transfers = { ScriptedRegistry.Transfer.LosesTheRequest })
                }
                val (answer, after) = When("Ada adopts Nibbles") {
                    testApp(shopWith(registry)) { shop: PetShop ->
                        shop.adopt(PetId(1), by = "Ada") to shop.find(PetId(1))
                    }
                }
                Then("she is told the registry is down") { answer shouldBeLeft RegistryDown(1) } and
                    And("Nibbles is back on the shelf") { after?.standing shouldBe OnShelf }
            }
        }

        test("an adoption the registry cannot confirm is pending, and settles once the registry answers") { _ ->
            story {
                val registry = Given(
                    "a registry that records Ada, loses its answer, and cannot be read back at first",
                ) {
                    ScriptedRegistry(
                        transfers = { ScriptedRegistry.Transfer.RecordsButLosesTheAnswer },
                        answers = { _, n -> n != 2 },
                    )
                }
                testApp(shopWith(registry)) { shop: PetShop ->
                    val answer = When("Ada adopts Nibbles") { shop.adopt(PetId(1), by = "Ada") }
                    Then("she is told it is pending: Nibbles is being adopted by her") {
                        answer.shouldBeRight { it.standing shouldBe Adopting("Ada") }
                    } and And("once the registry answers, Nibbles is hers").eventually(5.seconds) {
                        shop.find(PetId(1))?.standing shouldBeOrRetry Adopted("Ada")
                    }
                }
            }
        }

        test("an adoption whose confirmation was not written is pending, and settles") { _ ->
            story {
                val outbox = Given("an outbox whose first event cannot be written") { FailingFirstEvent() }
                val shop = Petshop.module.overriding(single<ChipRegistry> { ScriptedRegistry() })
                    .overriding(single<Outbox> { outbox })
                    .subgraph<PetShop>()
                testApp(shop) { shop: PetShop ->
                    val answer =
                        When("Ada adopts Nibbles, and the registry records her") { shop.adopt(PetId(1), by = "Ada") }
                    Then("she is told it is pending") {
                        answer.shouldBeRight { it.standing shouldBe Adopting("Ada") }
                    } and And("settling writes her adoption").eventually(5.seconds) {
                        shop.find(PetId(1))?.standing shouldBeOrRetry Adopted("Ada")
                    }
                }
            }
        }

        test("a return that could not be written is settled when the registry has no chip for the pet") { _ ->
            story {
                val outbox =
                    Given("an outbox whose second row write, the return, fails once") { FailingKeep(number = 2) }
                val shop =
                    Petshop.module.overriding(single<ChipRegistry> { ScriptedRegistry(unregistered = setOf(PetId(1))) })
                        .overriding(single<Outbox> { outbox })
                        .subgraph<PetShop>()
                testApp(shop) { shop: PetShop ->
                    val answer = When("Ada adopts Nibbles, who has no chip") { shop.adopt(PetId(1), by = "Ada") }
                    Then("she is told Nibbles has no chip") { answer shouldBeLeft NotChipped(1) } and
                        And(
                            "settling puts Nibbles back on the shelf, since the registry answered",
                        ).eventually(5.seconds) {
                            shop.find(PetId(1))?.standing shouldBeOrRetry OnShelf
                        }
                }
            }
        }

        test("an adoption the shop answered too late for is settled back onto the shelf") { _ ->
            story {
                val outbox = Given("an outbox that holds its first row write") { HeldFirstKeep() }
                val shop = Petshop.module.overriding(single<ChipRegistry> { ScriptedRegistry() })
                    .overriding(single<Outbox> { outbox })
                    .subgraph<PetShop>()
                testApp(shop) { shop: PetShop ->
                    When("Ada adopts Nibbles, the shop gives up waiting for the actor, and then the write lands") {
                        val gaveUp = shouldThrow<IllegalStateException>({ shop.adopt(PetId(1), by = "Ada") })
                        outbox.release.countDown()
                        gaveUp
                    } and Then("settling puts Nibbles back on the shelf, since the registry was never asked")
                        .eventually(10.seconds) { shop.find(PetId(1))?.standing shouldBeOrRetry OnShelf }
                }
            }
        }

        test("a pet that cannot be settled does not hold up another") { _ ->
            story {
                val registry = Given("a registry that loses every answer, and never answers about Nibbles again") {
                    ScriptedRegistry(
                        transfers = { ScriptedRegistry.Transfer.RecordsButLosesTheAnswer },
                        answers = { id, n -> n == 1 || (id == PetId(2) && n > 2) },
                    )
                }
                testApp(shopWith(registry)) { shop: PetShop ->
                    When("Ada adopts Nibbles, and then Bea adopts Barnaby") {
                        shop.adopt(PetId(1), by = "Ada").shouldBeRight { it.standing shouldBe Adopting("Ada") } and
                            shop.adopt(PetId(2), by = "Bea").shouldBeRight { it.standing shouldBe Adopting("Bea") }
                    } and Then("Barnaby settles while Nibbles still waits").eventually(5.seconds) {
                        (shop.find(PetId(2))?.standing shouldBeOrRetry Adopted("Bea")) and
                            (shop.find(PetId(1))?.standing shouldBeOrRetry Adopting("Ada"))
                    }
                }
            }
        }
    }
}

/** Throws on row write number [number], as a pool with nothing to lend does, and writes every other. */
private class FailingKeep(private val number: Int) : Outbox {
    private val keeps = AtomicInteger()

    override fun record(pet: Pet, numbered: (seq: Long) -> ShopEvent): ShopEvent = numbered(1)

    override fun keep(pet: Pet) {
        if (keeps.incrementAndGet() == number) throw SQLTransientConnectionException("the pool had nothing to lend")
    }

    override fun shelf(opening: List<Pet>): List<Pet> = opening

    override fun claim(limit: Int, publish: (List<Claimed>) -> List<ShopEvent>): List<ShopEvent> = emptyList()
}

/** Holds the first row write until the test lets it through, so it can land after the ask has given up. */
private class HeldFirstKeep : Outbox {
    val release = CountDownLatch(1)
    private val keeps = AtomicInteger()

    override fun record(pet: Pet, numbered: (seq: Long) -> ShopEvent): ShopEvent = numbered(1)

    override fun keep(pet: Pet) {
        if (keeps.incrementAndGet() == 1) check(release.await(10, TimeUnit.SECONDS)) { "the write was never released" }
    }

    override fun shelf(opening: List<Pet>): List<Pet> = opening

    override fun claim(limit: Int, publish: (List<Claimed>) -> List<ShopEvent>): List<ShopEvent> = emptyList()
}

/** Throws on the first event, as a pool with nothing to lend does, then writes everything. */
private class FailingFirstEvent : Outbox {
    private val events = AtomicInteger()

    override fun record(pet: Pet, numbered: (seq: Long) -> ShopEvent): ShopEvent {
        val seq = events.incrementAndGet().toLong()
        if (seq == 1L) throw SQLTransientConnectionException("the pool had nothing to lend")
        return numbered(seq)
    }

    override fun keep(pet: Pet) = Unit

    override fun shelf(opening: List<Pet>): List<Pet> = opening

    override fun claim(limit: Int, publish: (List<Claimed>) -> List<ShopEvent>): List<ShopEvent> = emptyList()
}
