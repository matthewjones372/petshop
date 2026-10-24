package petshop.app

import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.fold
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.logSpan
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.metricTagged
import io.github.matthewjones372.lark.otel.tracedSpan
import io.github.matthewjones372.lark.timed
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import petshop.domain.Adopting
import petshop.domain.AlreadyAdopted
import petshop.domain.BeingAdopted
import petshop.domain.NoSuchPet
import petshop.domain.NotChipped
import petshop.domain.NotRecorded
import petshop.domain.Pet
import petshop.domain.PetId
import petshop.domain.PetShop
import petshop.domain.PetShopError
import petshop.domain.RegistryDown

/** The shop's logs, trace and metrics around [ShopFront.adopt]; reads pass straight through. */
class ObservedShop(private val front: ShopFront, private val tracer: Tracer) : PetShop {

    override fun all(): List<Pet> = front.all()

    override fun find(id: PetId): Pet? = front.find(id)

    /** OpenTelemetry's context lives in a `LarkLocal` here; a `ThreadLocal` would not survive the ask. */
    override fun adopt(id: PetId, by: String): Either<PetShopError, Pet> =
        logAnnotated("pet_id" to id.value.toString(), "adopted_by" to by) {
            tracer.tracedSpan("adopt") {
                // Every line about this adoption carries its trace, so a log line leads to the trace in Tempo.
                logAnnotated("trace_id" to Span.current().spanContext.traceId) {
                    logSpan("adopt") {
                        // Timed around the ask so refusals, often the slow calls, are in the distribution.
                        timed("petshop.adopt.duration") {
                            val report = front.report(id, by)
                            report.warnings.forEach { warning -> logWarn(warning) }
                            report.answer.also { answer ->
                                answer.fold(
                                    { no ->
                                        logWarn(no.message)
                                        // Every branch tags only "outcome": Prometheus needs one label
                                        // set per metric name and silently drops a series with another.
                                        metricTagged("outcome" to no.outcome()) {
                                            counter("petshop.adoptions").increment()
                                        }
                                    },
                                    { pet ->
                                        val pending = pet.standing is Adopting
                                        logInfo(if (pending) "${pet.name} is being adopted" else "adopted ${pet.name}")
                                        metricTagged("outcome" to if (pending) "pending" else "taken") {
                                            counter("petshop.adoptions").increment()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
}

private fun PetShopError.outcome(): String = when (this) {
    is NoSuchPet -> "no_such_pet"
    is AlreadyAdopted -> "already_adopted"
    is BeingAdopted -> "being_adopted"
    is NotChipped -> "not_chipped"
    is RegistryDown -> "registry_down"
    is NotRecorded -> "not_recorded"
}
