package petshop.app

import io.github.matthewjones372.surefold.Carriers
import io.github.matthewjones372.surefold.Schedule
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.boundTo
import io.github.matthewjones372.surefold.app.closingOf
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.counter
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.map
import io.github.matthewjones372.surefold.fail.mapLeft
import io.github.matthewjones372.surefold.increment
import io.github.matthewjones372.surefold.kafka.DecodeError
import io.github.matthewjones372.surefold.kafka.Decoder
import io.github.matthewjones372.surefold.kafka.Kafka
import io.github.matthewjones372.surefold.kafka.Producer
import io.github.matthewjones372.surefold.kafka.Published
import io.github.matthewjones372.surefold.kafka.Topic
import io.github.matthewjones372.surefold.kafka.carrying
import io.github.matthewjones372.surefold.kafka.consume
import io.github.matthewjones372.surefold.kafka.deadLetters
import io.github.matthewjones372.surefold.kafka.divertLefts
import io.github.matthewjones372.surefold.kafka.mapRecord
import io.github.matthewjones372.surefold.kafka.producer
import io.github.matthewjones372.surefold.kafka.record
import io.github.matthewjones372.surefold.kafka.runCommitting
import io.github.matthewjones372.surefold.kafka.within
import io.github.matthewjones372.surefold.logWarn
import io.github.matthewjones372.surefold.stream.Run
import io.github.matthewjones372.surefold.stream.restartOnDefect
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.LongDeserializer
import org.apache.kafka.common.serialization.LongSerializer
import petshop.domain.ShopEvent
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An unreadable record goes to [deadLetters] and is committed past, so one bad record does not stop the
 * topic. A schema registry that cannot be asked restarts the read from the last commit; any other defect, a throw
 * from the handler among them, ends it as `Died`. Nothing unhandled is committed either way.
 */
class KafkaBus(
    private val topic: Topic,
    bootstrap: String,
    private val group: String,
    private val registry: Map<String, Any>,
    private val events: Producer<Long, ShopEvent>,
    letters: Producer<ByteArray?, ByteArray?>,
    deadLetters: ((DecodeError) -> Unit)? = null,
) : EventBus {

    private val consumer: Map<String, Any> = mapOf(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
        ConsumerConfig.GROUP_ID_CONFIG to group,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
    )

    private val deadLetters: (DecodeError) -> Unit =
        deadLetters ?: letters.deadLetters(Topic("${topic.name}.dead-letters")).counted()

    override fun publish(event: ShopEvent): Either<BusRefused, ShopEvent> =
        events.publish(topic.record(event.seq, event).carrying(Carriers.capture()))
            .mapLeft { failed -> BusRefused(event.seq, "${failed.cause}") }
            // The publisher's span is current, and the partition and offset are known once the broker has the record.
            .map { published -> event.also { Span.current().setAllAttributes(sent(published, it.seq)) } }

    override fun consume(each: (ShopEvent) -> Unit): Run<Nothing, Long> = consumeFrom { event, _ -> each(event) }

    override fun consumeFrom(each: (ShopEvent, Attributes) -> Unit): Run<Nothing, Long> = Kafka.consume(
        consumer,
        topic,
        // The seq as a number, so anything reading the topic orders keys as the events were numbered.
        key = Decoder(LongDeserializer()) { false },
        value = Decoder(ShopEventDeserializer(registry), transient = ::registryDown),
    )
        .divertLefts(deadLetters)
        .mapRecord { record -> record.within { each(record.value(), received(record)) } }
        // Only an outage waits and retries: a handler that throws would throw again on the same record, for ever.
        .restartOnDefect(registryBack)
        .runCommitting()

    private fun received(record: ConsumerRecord<Long, ShopEvent>): Attributes = Attributes.builder()
        .put(Messaging.system, "kafka")
        .put(Messaging.operation, "process")
        .put(Messaging.destination, record.topic())
        .put(Messaging.partition, "${record.partition()}")
        .put(Messaging.offset, record.offset())
        .put(Messaging.key, "${record.key()}")
        .put(Messaging.id, "${record.value().seq}")
        .put(Messaging.group, group)
        .build()
}

private fun sent(published: Published, seq: Long): Attributes = Attributes.builder()
    .put(Messaging.system, "kafka")
    .put(Messaging.operation, "send")
    .put(Messaging.destination, published.topic)
    .put(Messaging.partition, "${published.partition}")
    .put(Messaging.offset, published.offset)
    .put(Messaging.key, "$seq")
    .put(Messaging.id, "$seq")
    .build()

/** The bus and the two producers it sends with, each a node the graph closes after the bus. */
fun kafkaBus(
    topic: Topic,
    bootstrap: String,
    group: String,
    registry: Map<String, Any>,
    deadLetters: ((DecodeError) -> Unit)? = null,
): Module = // Named rather than inferred: Java serializers make the inferred key a platform type that nothing matches.
    closingOf<Producer<Long, ShopEvent>>(
        { -> Kafka.producer(producing(bootstrap), LongSerializer(), ShopEventSerializer(registry)) },
    ) +
        closingOf<Producer<ByteArray?, ByteArray?>>(
            { -> Kafka.producer(producing(bootstrap), ByteArraySerializer(), ByteArraySerializer()) },
        ) +
        single { events: Producer<Long, ShopEvent>, letters: Producer<ByteArray?, ByteArray?> ->
            KafkaBus(topic, bootstrap, group, registry, events, letters, deadLetters)
        }.boundTo<EventBus>()

// A send not taken within five seconds, retries included, is refused and stays in the outbox.
private fun producing(bootstrap: String): Map<String, Any> = mapOf(
    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
    ProducerConfig.ACKS_CONFIG to "all",
    ProducerConfig.MAX_BLOCK_MS_CONFIG to SEND_WITHIN_MS,
    ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to SEND_WITHIN_MS - 1_000,
    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to SEND_WITHIN_MS,
)

private const val SEND_WITHIN_MS = 5_000

private val registryBack: Schedule<Throwable, *> =
    Schedule.exponential<Throwable>(100.milliseconds)
        .delayed { _, wait -> minOf(wait, 30.seconds) }
        .doWhile { thrown, _ -> registryDown(thrown) }

private fun ((DecodeError) -> Unit).counted(): (DecodeError) -> Unit = { bad ->
    this(bad)
    logWarn("an unreadable record at ${bad.topic}-${bad.partition}@${bad.offset}: ${bad.cause}")
    counter("petshop.bus.dead_letters").increment()
}
