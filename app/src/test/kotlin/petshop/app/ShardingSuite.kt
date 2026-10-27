package petshop.app

import io.github.matthewjones372.surefold.actor.Address
import io.github.matthewjones372.surefold.actor.Reply
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.Species

object ShardingSuite : Suite() {
    init {
        test("consecutive ids spread evenly over the shards") {
            for (shards in listOf(2, 8, 32, 128)) {
                val counts = IntArray(shards)
                val pets = 100_000
                for (id in 1L..pets) counts[shardOf(PetId(id), shards)]++
                val fair = pets / shards
                // Within a tenth of a fair share either way: `id mod shards` would be exact, but a skewed mix is not.
                (counts.min() > fair * 9 / 10 && counts.max() < fair * 11 / 10) shouldBe true
            }
        }

        test("one shard owns every pet") {
            (1L..1_000L).all { shardOf(PetId(it), 1) == 0 } shouldBe true
        }

        test("a shard's pets don't share their low bits, so they spread over a HashMap's buckets") {
            // id mod 8 gave each of 8 shards ids with the same three low bits, so 1 bucket in 8 was used.
            val shard0 = (1L..10_000L).filter { shardOf(PetId(it), 8) == 0 }
            shard0.map { it and 7 }.toSet().size shouldBe 8
        }

        test("every message about a pet names it, so it reaches the pet's shard; a ping names none") {
            val nibbles = Pet(PetId(7), "Nibbles", Species.Tortoise)
            Arrived(nibbles).petId() shouldBe PetId(7)
            Adopt(PetId(7), "Ada", nobody).petId() shouldBe PetId(7)
            Confirm(PetId(7), "Ada", nobody).petId() shouldBe PetId(7)
            Return(PetId(7), "Ada", nobody).petId() shouldBe PetId(7)
            Ping(nobody).petId() shouldBe null
        }
    }
}

/** Routing reads only the pet, never the reply. */
private val nobody = object : Reply<Any> {
    override val address: Address get() = error("routing never replies")

    override fun invoke(answer: Any) = Unit
}
