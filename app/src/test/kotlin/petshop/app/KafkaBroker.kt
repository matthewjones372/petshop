package petshop.app

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.resource
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
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.concurrent.TimeUnit

/** One broker for the whole run. Each test uses topics and groups of its own, so classes can share it. */
private object TestKafka {

    // The JVM image: kafka-native segfaults on about one start in twenty (two threads racing in musl's getpwuid).
    val container: KafkaContainer by lazy {
        KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
            // A group's first member is not kept waiting for others to join, which is 3s a test by default.
            .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
            .apply { start() }
    }
}

/** The run's one broker, started by the first suite that acquires it; Testcontainers stops it when the JVM ends. */
fun kafkaBroker(): Resource<KafkaBroker> = resource { KafkaBroker(TestKafka.container.bootstrapServers) }

class KafkaBroker internal constructor(val bootstrap: String) {

    fun send(topic: String, vararg values: String) {
        KafkaProducer(mapOf<String, Any>("bootstrap.servers" to bootstrap), StringSerializer(), StringSerializer())
            .use { producer ->
                values.forEach { value ->
                    producer.send(ProducerRecord(topic, value)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
    }

    fun committed(group: String, topic: String): Long? =
        Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
            admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)[TopicPartition(topic, 0)]?.offset()
        }

    /**
     * Reads to each partition's end offset and stops, so it never waits on a timeout. `assign` with no
     * group, so no offset the service sees changes.
     */
    fun <K, V> records(topic: String, key: Deserializer<K>, value: Deserializer<V>): List<ConsumerRecord<K, V>> =
        KafkaConsumer(
            mapOf<String, Any>(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap),
            key,
            value,
        ).use { reader ->
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
