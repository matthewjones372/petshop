package petshop.app

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.raise.either
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import petshop.domain.ChipRegistry
import petshop.domain.NotChipped
import petshop.domain.NotRegistered
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShopError
import petshop.domain.RegistryDown
import petshop.domain.RegistryError
import petshop.domain.Unreachable
import kotlin.time.Duration.Companion.seconds

/** Warnings are what went wrong on the way to [answer] without changing it, for the caller to log. */
data class AdoptionReport(val answer: Either<PetShopError, Pet>, val warnings: List<String>)

class Adoption(private val ref: ActorRef<Shop>, private val registry: ChipRegistry) {

    /** The actor decides first, so a lost race never calls the registry. */
    fun adopt(id: PetId, by: String): AdoptionReport {
        val warnings = mutableListOf<String>()
        val answer = either {
            val pet = ref.asked { reply -> Adopt(id, by, reply) }.bind()
            registry.lookup(id)
                .flatMap { chip -> registry.transfer(chip, to = by) }
                .onLeft { failure ->
                    warnings += "registry refused pet ${id.value}: $failure"
                    ref.ask(within = 3.seconds) { reply -> Returned(id, reply) }
                        .onLeft { unanswered ->
                            warnings += "the return of pet ${id.value} was not confirmed: $unanswered"
                        }
                }
                .mapLeft { failure -> failure.refusing(id) }
                .bind()
            pet
        }
        return AdoptionReport(answer, warnings)
    }
}

private fun RegistryError.refusing(id: PetId): PetShopError = when (this) {
    NotRegistered -> NotChipped(id.value)
    is Unreachable -> RegistryDown(id.value)
}

/** Throws when the actor stopped or timed out: that is a fault (a 500), not a declared refusal. */
private fun <A : Any> ActorRef<Shop>.asked(message: (Reply<A>) -> Shop): A =
    ask(within = 3.seconds, message).getOrElse { failure -> error("the shop did not answer: $failure") }
