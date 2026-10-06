package petshop.app

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
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

/**
 * One Kafka broker for the whole test run, in a container started the first time a test class asks for it,
 * as [TestPostgres] is. Testcontainers' reaper stops it once the JVM has gone. Each test uses topics and
 * groups of its own, so the classes can share it.
 */
private object TestKafka {

    // The native image starts in about a second, and runs KRaft, so there is no ZooKeeper beside it.
    val container: KafkaContainer by lazy {
        KafkaContainer(DockerImageName.parse("apache/kafka-native:4.3.1"))
            // A group's first member is not kept waiting for others to join, which is 3s a test by default.
            .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
            .apply { start() }
    }
}

/** The test run's Kafka broker, for a test class that registers it; [bootstrap] is where it answers. */
class KafkaBroker : BeforeAllCallback {

    val bootstrap: String get() = TestKafka.container.bootstrapServers

    override fun beforeAll(context: ExtensionContext) {
        TestKafka.container
    }

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

    private companion object {
        const val TIMEOUT_SECONDS = 30L
    }
}
