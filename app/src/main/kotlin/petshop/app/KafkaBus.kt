package petshop.app

import arrow.core.Either
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.kafka.DecodeError
import io.github.matthewjones372.lark.kafka.Decoder
import io.github.matthewjones372.lark.kafka.Kafka
import io.github.matthewjones372.lark.kafka.Topic
import io.github.matthewjones372.lark.kafka.consume
import io.github.matthewjones372.lark.kafka.deadLetters
import io.github.matthewjones372.lark.kafka.divertLefts
import io.github.matthewjones372.lark.kafka.mapRecord
import io.github.matthewjones372.lark.kafka.producer
import io.github.matthewjones372.lark.kafka.record
import io.github.matthewjones372.lark.kafka.runCommitting
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.stream.Run
import io.github.matthewjones372.lark.stream.restartOnDefect
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.LongDeserializer
import org.apache.kafka.common.serialization.LongSerializer
import petshop.domain.ShopEvent

/**
 * An unreadable record goes to [deadLetters] and is committed past, so one bad record does not stop the
 * topic. A schema registry that cannot be asked is a defect instead, and nothing unhandled is committed.
 */
class KafkaBus(
    private val topic: Topic,
    bootstrap: String,
    group: String,
    private val registry: Map<String, Any>,
    deadLetters: ((DecodeError) -> Unit)? = null,
) : EventBus {

    private val consumer: Map<String, Any> = mapOf(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
        ConsumerConfig.GROUP_ID_CONFIG to group,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
    )

    // A send not taken within five seconds, retries included, is refused and stays in the outbox.
    private val producing: Map<String, Any> = mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
        ProducerConfig.ACKS_CONFIG to "all",
        ProducerConfig.MAX_BLOCK_MS_CONFIG to SEND_WITHIN_MS,
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to SEND_WITHIN_MS - 1_000,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to SEND_WITHIN_MS,
    )

    private val events = Kafka.producer(producing, LongSerializer(), ShopEventSerializer(registry))

    private val letters = Kafka.producer(producing, ByteArraySerializer(), ByteArraySerializer())

    private val deadLetters: (DecodeError) -> Unit =
        deadLetters ?: letters.deadLetters(Topic("${topic.name}.dead-letters")).counted()

    override fun publish(event: ShopEvent): Either<BusRefused, ShopEvent> =
        events.publish(topic.record(event.seq, event))
            .mapLeft { failed -> BusRefused(event.seq, "${failed.cause}") }
            .map { event }

    override fun consume(each: (ShopEvent) -> Unit): Run<Nothing, Long> =
        Kafka.consume(
            consumer,
            topic,
            // The seq as a number, so anything reading the topic orders keys as the events were numbered.
            key = Decoder(LongDeserializer()) { false },
            value = Decoder(ShopEventDeserializer(registry), transient = ::registryDown),
        )
            .divertLefts(deadLetters)
            .mapRecord { record -> each(record.value()) }
            // A schema registry outage pauses the projection rather than ending it: restart from the last commit.
            .restartOnDefect(Schedule.exponential<Throwable>(100.milliseconds).delayed { _, wait -> minOf(wait, 30.seconds) })
            .runCommitting()

    override fun close() {
        events.close()
        letters.close()
    }

    private companion object {
        const val SEND_WITHIN_MS = 5_000
    }
}

private fun ((DecodeError) -> Unit).counted(): (DecodeError) -> Unit = { bad ->
    this(bad)
    logWarn("an unreadable record at ${bad.topic}-${bad.partition}@${bad.offset}: ${bad.cause}")
    counter("petshop.bus.dead_letters").increment()
}
