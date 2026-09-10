package petshop.app

import arrow.core.Either
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.probe
import arrow.core.Option
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.pekko.ask
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.pekko.actor
import io.github.matthewjones372.lark.app.typesafe.configured
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pekko.PelicanServer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.javadsl.Adapter
import petshop.api.petshopApi
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.PetShopError
import petshop.domain.Species
import java.time.Duration
import org.apache.pekko.actor.typed.ActorSystem as TypedSystem
import kotlin.time.Duration.Companion.seconds

data class Settings(val port: Int, val arrivalsEvery: Duration)

/** The catalogue the shop opens with, before any arrival. */
val opening: List<Pet> = listOf(
    Pet(PetId(1), "Nibbles", Species.Tortoise),
    Pet(PetId(2), "Barnaby", Species.Dog),
    Pet(PetId(3), "Mrs Peel", Species.Cat),
)

/** Reads and writes go to the actor, so there is one writer and no lock anywhere in this file. */
class ActorPetShop(
    private val ref: ActorRef<Shop>,
    private val system: ActorSystem,
) : PetShop {

    override fun all(): List<Pet> = ref.ask(system, asking) { replyTo -> Everything(replyTo) }

    override fun find(id: PetId): Pet? =
        ref.ask(system, asking) { replyTo: ActorRef<Option<Pet>> -> Find(id, replyTo) }
            .fold({ null }, { pet -> pet })

    override fun adopt(id: PetId, by: String): Either<PetShopError, Pet> =
        ref.ask(system, asking) { replyTo -> Adopt(id, by, replyTo) }
}

private val asking = 3.seconds

private val settings: Module =
    single<Config> { ConfigFactory.load() } +
        configured("petshop") { Settings(int("port"), of(Duration.ofSeconds(5)) { getDuration("arrivalsEvery") }) }

private val theShop: Module =
    single<ActorSystem> {
        install({ ActorSystem.create("petshop") }) { system, _ -> system.terminate() }
    } +
        // The typed view of the same system. Two types, two keys, and the one that spawns actors is not
    // the one Pelican binds a port with.
    single<TypedSystem<Void>, ActorSystem> { classic -> Adapter.toTyped(classic) } +
        actor("shop") { _: Settings -> shop(opening.associateBy { it.id }) } +
        singleOf(::ActorPetShop).boundTo<PetShop>()
            .probe("shop", timeout = 3.seconds) { shop: PetShop -> shop.all().isNotEmpty() }

private val web: Module =
    single { shop: PetShop, config: Settings, system: TypedSystem<Void>, _: Arrivals ->
        // The port is a resource like any other: bound here, unbound when the graph is given back,
        // which is what lets a load test start the whole application in its own process.
        install({
            petshopApi(shop).startWithDocs(system, port = config.port, docs = docs { docsPath = "/api-docs" })
        }) { server, _ ->
            server.stop()
        }
    }

val petshop: Module = settings + theShop + arrivals + web
