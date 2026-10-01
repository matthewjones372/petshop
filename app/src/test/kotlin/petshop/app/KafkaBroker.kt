package petshop.app

import io.github.embeddedkafka.EmbeddedK
import io.github.embeddedkafka.EmbeddedKafka
import io.github.embeddedkafka.EmbeddedKafkaConfig
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** A broker in the test JVM for one test class, started and stopped by JUnit 5 as the actor system is. */
class KafkaBroker : BeforeAllCallback, AfterAllCallback {

    private lateinit var kafka: EmbeddedK

    val bootstrap: String get() = "localhost:${kafka.config().kafkaPort()}"

    override fun beforeAll(context: ExtensionContext) {
        val config = EmbeddedKafkaConfig.apply(
            freePort(),
            freePort(),
            // A group's first member is not kept waiting for others to join, which is 3s a test by default.
            brokerProperties(),
            EmbeddedKafkaConfig.`apply$default$4`(),
            EmbeddedKafkaConfig.`apply$default$5`(),
        )
        kafka = EmbeddedKafka.start(config)
    }

    override fun afterAll(context: ExtensionContext) = kafka.stop(true)

    fun send(topic: String, vararg values: String) {
        KafkaProducer(mapOf<String, Any>("bootstrap.servers" to bootstrap), StringSerializer(), StringSerializer())
            .use { producer ->
                values.forEach { value ->
                    producer.send(ProducerRecord(topic, value)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
    }

    /** The offset the group has committed on the topic's only partition, or null for none. */
    fun committed(group: String, topic: String): Long? =
        Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
            admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)[TopicPartition(topic, 0)]?.offset()
        }

    /**
     * Every record on [topic] as the broker holds it now, oldest first per partition. Reads up to each
     * partition's end offset and stops, so it answers at once rather than polling until a timeout, and an
     * empty topic is an empty list. `assign` with no group: reading changes no offset the service sees.
     * Lark spec 0117 proposes this for lark-kafka-test; it can move there when that lands.
     */
    fun <K, V> records(topic: String, key: Deserializer<K>, value: Deserializer<V>): List<ConsumerRecord<K, V>> =
        KafkaConsumer(mapOf<String, Any>(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap), key, value).use { reader ->
            val partitions = reader.partitionsFor(topic).map { TopicPartition(topic, it.partition()) }
            reader.assign(partitions)
            reader.seekToBeginning(partitions)
            val end = reader.endOffsets(partitions)
            buildList {
                while (partitions.any { reader.position(it) < end.getValue(it) }) {
                    addAll(reader.poll(Duration.ofMillis(100)))
                }
            }
        }

    // Scala's `updated` widens its value type, which Kotlin reads as a Map of Any; the entry added is a String.
    @Suppress("UNCHECKED_CAST")
    private fun brokerProperties(): scala.collection.immutable.Map<String, String> =
        EmbeddedKafkaConfig.`apply$default$3`()
            .updated("group.initial.rebalance.delay.ms", "0") as scala.collection.immutable.Map<String, String>

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
    }
}
