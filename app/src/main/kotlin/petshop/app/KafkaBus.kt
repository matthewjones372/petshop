package petshop.app

import arrow.core.Either
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.kafka.DecodeError
import io.github.matthewjones372.lark.kafka.Decoder
import io.github.matthewjones372.lark.kafka.Kafka
import io.github.matthewjones372.lark.kafka.Topic
import io.github.matthewjones372.lark.kafka.consume
import io.github.matthewjones372.lark.kafka.divertLefts
import io.github.matthewjones372.lark.kafka.mapRecord
import io.github.matthewjones372.lark.kafka.runCommitting
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.stream.Run
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import petshop.domain.ShopEvent
import java.util.concurrent.TimeUnit

/**
 * The bus on Kafka: each event an Avro record in the registry's wire format, keyed by its `seq`, and
 * read back by a consumer loop that runs on whichever backend the graph names.
 *
 * A record that cannot be read is not the reader's failure: it goes to [deadLetters] and is committed
 * past, so one bad record does not stop the topic. A registry that cannot be asked is a failure, and the
 * run ends `Died` for its owner to start again, having committed nothing it did not hand over.
 */
class KafkaBus(
    private val topic: Topic,
    bootstrap: String,
    private val group: String,
    private val registry: Map<String, Any>,
    private val deadLetters: (DecodeError) -> Unit = ::logDeadLetter,
) : EventBus, AutoCloseable {

    private val consumer: Map<String, Any> = mapOf(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
        ConsumerConfig.GROUP_ID_CONFIG to group,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
    )

    private val producer = KafkaProducer(
        mapOf<String, Any>(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap, ProducerConfig.ACKS_CONFIG to "all"),
        StringSerializer(),
        ShopEventSerializer(registry),
    )

    /** Taken once the broker has it: a refusal, or no answer in time, leaves the event in the outbox. */
    override fun publish(event: ShopEvent): Either<BusRefused, ShopEvent> =
        Either.catch { producer.send(ProducerRecord(topic.name, event.seq.toString(), event)).get(5, TimeUnit.SECONDS) }
            .mapLeft { BusRefused(event.seq, "$it") }
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

    override fun close() = producer.close()
}

private fun logDeadLetter(bad: DecodeError) {
    logWarn("an unreadable record at ${bad.topic}-${bad.partition}@${bad.offset}: ${bad.cause}")
    counter("petshop.bus.dead_letters").increment()
}
