package petshop.app

import arrow.core.Either
import arrow.core.right
import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.Hub
import io.github.matthewjones372.lark.stream.Run
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.TestStreams
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.test.story
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.jupiter.api.Test
import petshop.domain.Pet
import petshop.domain.PetArrived
import petshop.domain.PetId
import petshop.domain.ShopEvent
import petshop.domain.Species
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Spans as the SDK ends them, to read their kind and attributes. */
internal class Ended : AutoCloseable {
    val spans = ConcurrentLinkedQueue<SpanData>()
    private val provider = SdkTracerProvider.builder()
        .addSpanProcessor(
            SimpleSpanProcessor.create(
                object : SpanExporter {
                    override fun export(ended: Collection<SpanData>): CompletableResultCode {
                        spans.addAll(ended)
                        return CompletableResultCode.ofSuccess()
                    }

                    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

                    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
                },
            ),
        )
        .build()
    val tracer: Tracer = provider.get("test")

    fun named(name: String): List<SpanData> = spans.filter { it.name == name }

    override fun close() = provider.close()
}

internal val waffle = PetArrived(seq = 4, pet = Pet(PetId(104), "Waffle", Species.Dog))

/** Hands out its events once, to the first claim. */
private class Once(vararg events: ShopEvent) : Outbox {
    private val waiting = ConcurrentLinkedQueue(events.toList())

    override fun record(pet: Pet, numbered: (seq: Long) -> ShopEvent): ShopEvent = numbered(0)

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

class MessagingSpansSpec {

    @Test
    fun `the relay publishes each event in a producer span`() = story {
        Ended().use { ended ->
            val clock = TestClock()
            val every = 50.milliseconds
            Given("Waffle's arrival in the outbox, and a relay") {
                relay(every, Once(waffle), Taken, ended.tracer).start(TestStreams(clock))
            }.use { _ ->
                When("the relay ticks") { clock.adjust(every) }
                Then("its publish span is a producer's") {
                    ended.named("publish").map { it.kind } shouldBe listOf(SpanKind.PRODUCER)
                }
            }
        }
    }

    @Test
    fun `the projection handles each event in a consumer span, with where the bus read it from`() = story {
        Ended().use { ended ->
            val reading = Given("a projection, on a bus that says where it read each event from") {
                (
                    projection + feed +
                        single<StreamBackend> { Forks() } +
                        single<EventBus> { Placed(HubBus(Hub(16))) } +
                        single<Tracer> { ended.tracer } +
                        single { bus: EventBus, projection: Projection -> Projected(bus, projection) }
                    ).subgraph<Projected>()
            }
            testApp(reading) { app: Projected ->
                When("Waffle's arrival is published") { app.bus.publish(waffle) }
                Then(
                    "the projection's process span is a consumer's, with the bus's attributes",
                ).eventually(30.seconds) {
                    val process = ended.named("process").single()
                    process.kind shouldBe SpanKind.CONSUMER
                    process.attributes.asMap() shouldBe Placed.where.asMap()
                }
            }
        }
    }
}
