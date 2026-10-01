package petshop.app

import arrow.core.Either
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
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import petshop.domain.ShopEvent

/**
 * The bus on Kafka: each event an Avro record in the registry's wire format, keyed by its `seq`, and
 * read back by a consumer loop that runs on whichever backend the graph names.
 *
 * A record that cannot be read is not the reader's failure: it goes to [deadLetters], by default the
 * topic `<topic>.dead-letters` as it was read, and is committed past once it is there, so one bad
 * record does not stop the topic. A registry that cannot be asked is a failure, and the run ends `Died`
 * for its owner to start again, having committed nothing it did not hand over.
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

    // A send the broker has not taken within five seconds, retries included, is refused, and the event
    // stays in the outbox for the relay's next pass.
    private val producing: Map<String, Any> = mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
        ProducerConfig.ACKS_CONFIG to "all",
        ProducerConfig.MAX_BLOCK_MS_CONFIG to SEND_WITHIN_MS,
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to SEND_WITHIN_MS - 1_000,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to SEND_WITHIN_MS,
    )

    private val events = Kafka.producer(producing, StringSerializer(), ShopEventSerializer(registry))

    private val letters = Kafka.producer(producing, ByteArraySerializer(), ByteArraySerializer())

    private val deadLetters: (DecodeError) -> Unit =
        deadLetters ?: letters.deadLetters(Topic("${topic.name}.dead-letters")).counted()

    /** Taken once the broker has it: a refusal, or no answer in time, leaves the event in the outbox. */
    override fun publish(event: ShopEvent): Either<BusRefused, ShopEvent> =
        events.publish(topic.record(event.seq.toString(), event))
            .mapLeft { failed -> BusRefused(event.seq, "${failed.cause}") }
            .map { event }

    override fun consume(each: (ShopEvent) -> Unit): Run<Nothing, Long> =
        Kafka.consume(
            consumer,
            topic,
            key = Decoder.string(),
            value = Decoder(ShopEventDeserializer(registry), transient = ::registryDown),
        )
            .divertLefts(deadLetters)
            .mapRecord { record -> each(record.value()) }
            .runCommitting()

    override fun close() {
        events.close()
        letters.close()
    }

    private companion object {
        const val SEND_WITHIN_MS = 5_000
    }
}

/** A dead letter written by [this], and logged and counted once it is. */
private fun ((DecodeError) -> Unit).counted(): (DecodeError) -> Unit = { bad ->
    this(bad)
    logWarn("an unreadable record at ${bad.topic}-${bad.partition}@${bad.offset}: ${bad.cause}")
    counter("petshop.bus.dead_letters").increment()
}
