package petshop.app

import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeSameInstanceAs
import petshop.domain.Adopted
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.Species
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val nibbles = Pet(PetId(1), "Nibbles", Species.Tortoise)
private val barnaby = Pet(PetId(2), "Barnaby", Species.Dog)

object ShopWindowSuite : Suite() {
    init {
        test("the catalogue is the same instance until a pet changes, and a new one after") {
            val window = ShopWindow().also { it.show(mapOf(nibbles.id to nibbles)) }
            val first = window.listed()
            window.listed() shouldBeSameInstanceAs first

            window.put(nibbles.copy(standing = Adopted("Ada")))
            val after = window.listed()
            (after === first) shouldBe false
            after.single().adopted shouldBe true
            window.listed() shouldBeSameInstanceAs after
        }

        test("pets are listed in id order, whichever order they were put in") {
            val window = ShopWindow()
            window.put(barnaby)
            window.put(nibbles)
            window.pets() shouldBe listOf(nibbles, barnaby)
            window.listed().map { it.id } shouldBe listOf(1L, 2L)
        }

        test("a pet put is found, and listed by the next read") {
            val window = ShopWindow().also { it.show(mapOf(nibbles.id to nibbles)) }
            window.listed()
            window.put(barnaby)
            window.find(barnaby.id) shouldBe barnaby
            window.listed().map { it.id } shouldBe listOf(1L, 2L)
        }

        test("a read never misses a write that returned before it, with writers and readers racing") {
            val window = ShopWindow()
            val writers = 8
            val each = 500
            val pool = Executors.newFixedThreadPool(writers * 2)
            val start = CountDownLatch(1)
            val misses = java.util.concurrent.atomic.AtomicInteger()
            repeat(writers) { w ->
                pool.execute {
                    start.await()
                    repeat(each) { i ->
                        val id = PetId(w * each + i + 1L)
                        window.put(Pet(id, "Pet $id", Species.Cat))
                        if (window.listed().none { it.id == id.value }) misses.incrementAndGet()
                    }
                }
                pool.execute {
                    start.await()
                    repeat(each) { window.listed() }
                }
            }
            start.countDown()
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS) shouldBe true
            misses.get() shouldBe 0
            window.listed().size shouldBe writers * each
        }
    }
}
