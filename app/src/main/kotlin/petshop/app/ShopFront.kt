package petshop.app

import arrow.core.Either
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.actor.actor
import io.github.matthewjones372.lark.app.actor.actors
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.closingOf
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.stream.StreamBackend
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
        // shelf reads the pets table, so a restart opens the shop as it was left.
        actor<Shop, Outbox, ShopWindow>("shop") { outbox, window ->
            shop(outbox, window, outbox.shelf(opening).associateBy { it.id })
        } +
        closingOf { ref: ActorRef<Shop>, registry: ChipRegistry, window: ShopWindow, streams: StreamBackend ->
            Adoption(ref, registry, window, streams)
        } +
        single { window: ShopWindow, adoption: Adoption, tracer: Tracer ->
            ObservedShop(ShopFront(window, adoption), tracer)
        }.boundTo<PetShop>()
