// avro4k's serde for Confluent's registry is all experimental; it is also the one avro4k keeps, where the
// GenericData round trip it replaces is deprecated.
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

/** The schema every event on the topic is written with, derived from the wire classes. */
val shopEventSchema: Schema = Avro.schema<ShopEventRecord>()

/** A domain event as the record the shop publishes. */
fun ShopEvent.toWire(): WireEvent = transformInto()

/** A record read back as the domain event it was. */
fun WireEvent.toDomain(): ShopEvent = transformInto()

/**
 * A domain event in the schema registry's wire format: avro4k's serializer registers [shopEventSchema]
 * under the topic's subject and writes its id before the Avro body, as Confluent's own does.
 */
class ShopEventSerializer(registry: Map<String, Any>) : Serializer<ShopEvent> {

    private val avro = SpecificAvro4kKafkaSerializer<ShopEventRecord>(isKey = false, props = registry)

    override fun serialize(topic: String, event: ShopEvent): ByteArray =
        checkNotNull(avro.serialize(topic, ShopEventRecord(event.toWire()))) { "An event serialized to nothing" }

    override fun close() = avro.close()
}

/** The other way: the id names the writer's schema, which the registry answers with. */
class ShopEventDeserializer(registry: Map<String, Any>) : Deserializer<ShopEvent> {

    private val avro = SpecificAvro4kKafkaDeserializer<ShopEventRecord>(isKey = false, props = registry)

    override fun deserialize(topic: String, data: ByteArray): ShopEvent =
        checkNotNull(avro.deserialize(topic, data)) { "A record on $topic deserialized to nothing" }.event.toDomain()

    override fun deserialize(topic: String, headers: Headers, data: ByteArray): ShopEvent = deserialize(topic, data)

    override fun close() = avro.close()
}

/**
 * A registry that cannot be asked, as against a record that cannot be read. Confluent's deserializer
 * throws the same `SerializationException` for both; what it wraps says which. A 5xx arrives as a
 * `RestClientException`, not an `IOException`, and missing it would send every record to dead letters.
 */
fun registryDown(thrown: Throwable): Boolean =
    generateSequence(thrown) { it.cause }
        .any { it is IOException || (it is RestClientException && it.status >= 500) }
