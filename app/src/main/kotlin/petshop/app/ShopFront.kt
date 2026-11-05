package petshop.app

import dev.surefold.actor.ActorRef
import dev.surefold.actor.Route
import dev.surefold.actor.group
import dev.surefold.app.Module
import dev.surefold.app.actor.Actors
import dev.surefold.app.actor.actors
import dev.surefold.app.actor.spawn
import dev.surefold.app.boundTo
import dev.surefold.app.closingOf
import dev.surefold.app.single
import dev.surefold.app.singleOf
import dev.surefold.app.typesafe.config
import dev.surefold.fail.Either
import dev.surefold.stream.StreamBackend
import io.opentelemetry.api.trace.Tracer
import petshop.domain.ChipRegistry
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.PetShopError
import petshop.domain.Species

val opening: List<Pet> = listOf(
    Pet(PetId(1), "Nibbles", Species.Tortoise),
    Pet(PetId(2), "Barnaby", Species.Dog),
    Pet(PetId(3), "Mrs Peel", Species.Cat),
)

/** How many shop actors split the pets between them (`petshop.shards`); 1 is one actor for the whole shop. */
data class ShopShards(val count: Int)

/** Every message about one pet reaches the one shard that owns it; a ping, about none, goes to the first. */
private val byPet = Route<Shop> { message, of -> message.petId()?.let { shardOf(it, of) } ?: 0 }

class ShopFront(private val window: ShopWindow, private val adoption: Adoption) : PetShop {

    override fun all(): List<Pet> = window.pets()

    override fun find(id: PetId): Pet? = window.find(id)

    override fun adopt(id: PetId, by: String): Either<PetShopError, Pet> = report(id, by).answer

    /** [adopt] with the warnings it would drop, for [ObservedShop] to log. */
    fun report(id: PetId, by: String): AdoptionReport = adoption.adopt(id, by)
}

val theShop: Module =
    actors() +
        singleOf(::ShopWindow) +
        // shelf reads the pets table, so a restart opens the shop as it was left. It is read once and split, and
        // every shard shows its part before the ref is handed out, so Adoption's start-up sees the whole shelf.
        config<ShopShards>("petshop") { ShopShards(int("shards")) } +
        single { actors: Actors, outbox: Outbox, window: ShopWindow, sharding: ShopShards ->
            val shelf = outbox.shelf(opening).associateBy { it.id }
            val shards = sharding.count
            require(shards >= 1) { "petshop.shards must be at least 1, was $shards" }
            if (shards == 1) {
                spawn(actors, "shop", shop(outbox, window, shelf))
            } else {
                val refs = List(shards) { i ->
                    spawn(actors, "shop-$i", shop(outbox, window, shelf.filterKeys { shardOf(it, shards) == i }))
                }
                group(refs, byPet)
            }
        } +
        closingOf { ref: ActorRef<Shop>, registry: ChipRegistry, window: ShopWindow, streams: StreamBackend ->
            Adoption(ref, registry, window, streams)
        } +
        single { window: ShopWindow, adoption: Adoption, tracer: Tracer ->
            ObservedShop(ShopFront(window, adoption), tracer)
        }.boundTo<PetShop>()
