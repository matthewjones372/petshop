// avro4k's Confluent serde is experimental, but the GenericData path it replaces is deprecated.
@file:OptIn(ExperimentalAvro4kApi::class)

package petshop.app

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.kafka.confluent.SpecificAvro4kKafkaDeserializer
import com.github.avrokotlin.avro4k.kafka.confluent.SpecificAvro4kKafkaSerializer
import com.github.avrokotlin.avro4k.schema
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import io.github.matthewjones372.kimney.transformInto
import org.apache.avro.Schema
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serializer
import petshop.app.wire.ShopEventRecord
import petshop.app.wire.WireEvent
import petshop.domain.ShopEvent
import java.io.IOException

val shopEventSchema: Schema = Avro.schema<ShopEventRecord>()

fun ShopEvent.toWire(): WireEvent = transformInto()

fun WireEvent.toDomain(): ShopEvent = transformInto()

class ShopEventSerializer(registry: Map<String, Any>) : Serializer<ShopEvent> {

    private val avro = SpecificAvro4kKafkaSerializer<ShopEventRecord>(isKey = false, props = registry)

    override fun serialize(topic: String, event: ShopEvent): ByteArray =
        checkNotNull(avro.serialize(topic, ShopEventRecord(event.toWire()))) { "An event serialized to nothing" }

    override fun close() = avro.close()
}

class ShopEventDeserializer(registry: Map<String, Any>) : Deserializer<ShopEvent> {

    private val avro = SpecificAvro4kKafkaDeserializer<ShopEventRecord>(isKey = false, props = registry)

    override fun deserialize(topic: String, data: ByteArray): ShopEvent =
        checkNotNull(avro.deserialize(topic, data)) { "A record on $topic deserialized to nothing" }.event.toDomain()

    override fun deserialize(topic: String, headers: Headers, data: ByteArray): ShopEvent = deserialize(topic, data)

    override fun close() = avro.close()
}

/**
 * Confluent throws the same `SerializationException` for a down registry and an unreadable record; the
 * cause says which. A 5xx is a `RestClientException`, and missing it would dead-letter every record.
 */
fun registryDown(thrown: Throwable): Boolean = generateSequence(thrown) { it.cause }
    .any { it is IOException || (it is RestClientException && it.status >= 500) }
