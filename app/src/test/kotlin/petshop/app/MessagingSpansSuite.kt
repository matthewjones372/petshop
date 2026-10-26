package petshop.app

import io.github.matthewjones372.surefold.TestClock
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.app.subgraph
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.right
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.story
import io.github.matthewjones372.surefold.otel.Spans
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.Hub
import io.github.matthewjones372.surefold.stream.Run
import io.github.matthewjones372.surefold.stream.StreamBackend
import io.github.matthewjones372.surefold.stream.TestStreams
import io.github.matthewjones372.surefold.stream.start
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import petshop.domain.Pet
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.ShopEvent
import petshop.domain.Species
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal val waffle = PetArrived(seq = 4, pet = Pet(PetId(104), "Waffle", Species.Dog))

/** Hands out its events once, to the first claim. */
private class Once(vararg events: ShopEvent) : Outbox {
    private val waiting = ConcurrentLinkedQueue(events.toList())

    override fun record(pet: Pet, numbered: (seq: Long) -> ShopEvent): ShopEvent = numbered(0)

    override fun keep(pet: Pet) = Unit

    override fun shelf(opening: List<Pet>): List<Pet> = opening

    override fun claim(limit: Int, publish: (List<Claimed>) -> List<ShopEvent>): List<ShopEvent> =
        generateSequence { waiting.poll() }.toList().takeIf { it.isNotEmpty() }?.let { publish(it.map(::Claimed)) }
            .orEmpty()
}

private object Taken : EventBus {
    override fun publish(event: ShopEvent): Either<BusRefused, ShopEvent> = event.right()

    override fun consume(each: (ShopEvent) -> Unit): Run<Nothing, Long> = error("Taken is only published to")
}

/** The in-process hub, saying each event was read from orders 3 at offset 41. */
private class Placed(private val hub: HubBus) : EventBus by hub {
    override fun consumeFrom(each: (ShopEvent, Attributes) -> Unit): Run<Nothing, Long> =
        hub.consume { each(it, where) }

    companion object {
        val where: Attributes = Attributes.builder()
            .put(Messaging.destination, "orders")
            .put(Messaging.partition, "3")
            .put(Messaging.offset, 41L)
            .build()
    }
}

private class Projected(val bus: EventBus, val projection: Projection)

object MessagingSpansSuite : Suite() {
    init {
        test("the relay publishes each event in a producer span") {
            story {
                Spans().use { spans ->
                    val clock = TestClock()
                    val every = 50.milliseconds
                    Given("Waffle's arrival in the outbox, and a relay") {
                        relay(every, Once(waffle), Taken, spans.tracer).start(TestStreams(clock))
                    }.use { _ ->
                        When("the relay ticks") { clock.adjust(every) }
                        Then("its publish span is a producer's") {
                            spans.named("publish").map { it.kind } shouldBe listOf(SpanKind.PRODUCER)
                        }
                    }
                }
            }
        }

        test("the projection handles each event in a consumer span, with where the bus read it from") {
            story {
                Spans().use { spans ->
                    val reading = Given("a projection, on a bus that says where it read each event from") {
                        (
                            projection + feed +
                                single<StreamBackend> { Forks() } +
                                single<EventBus> { Placed(HubBus(Hub(16))) } +
                                single<Tracer> { spans.tracer } +
                                single { bus: EventBus, projection: Projection -> Projected(bus, projection) }
                            ).subgraph<Projected>()
                    }
                    testApp(reading) { app: Projected ->
                        When("Waffle's arrival is published") { app.bus.publish(waffle) }
                        Then(
                            "the projection's process span is a consumer's, with the bus's attributes",
                        ).eventually(30.seconds) {
                            val process = spans.named("process").single()
                            process.kind shouldBe SpanKind.CONSUMER
                            process.attributes.asMap() shouldBe Placed.where.asMap()
                        }
                    }
                }
            }
        }

        test("a bus with no offsets says nothing of where it read an event from") {
            story {
                val read = ConcurrentLinkedQueue<Pair<ShopEvent, Attributes>>()
                val bus = Given("the in-process bus, with Waffle's arrival on it") {
                    HubBus(Hub(16)).also { it.publish(waffle) }
                }
                When("a reader reads it with where it came from") {
                    bus.consumeFrom { event, where -> read.add(event to where) }.start(Forks())
                }.use { _ ->
                    Then("it reads the arrival, with no attributes").eventually(30.seconds) {
                        read.toList() shouldBe listOf(waffle to Attributes.empty())
                    }
                }
            }
        }
    }
}
