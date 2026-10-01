package petshop.app

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromGenericData
import com.github.avrokotlin.avro4k.encodeToGenericData
import com.github.avrokotlin.avro4k.schema
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import io.confluent.kafka.serializers.KafkaAvroSerializer
import io.github.matthewjones372.kimney.transformInto
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
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
 * A domain event in the schema registry's wire format: Confluent's serializer registers [shopEventSchema]
 * under the topic's subject and writes its id before the Avro body.
 */
class ShopEventSerializer(registry: Map<String, Any>) : Serializer<ShopEvent> {

    private val avro = KafkaAvroSerializer().apply { configure(registry, false) }

    override fun serialize(topic: String, event: ShopEvent): ByteArray =
        avro.serialize(topic, Avro.encodeToGenericData(shopEventSchema, ShopEventRecord(event.toWire())))

    override fun close() = avro.close()
}

/** The other way: the id names the writer's schema, which the registry answers with. */
class ShopEventDeserializer(registry: Map<String, Any>) : Deserializer<ShopEvent> {

    private val avro = KafkaAvroDeserializer().apply { configure(registry, false) }

    override fun deserialize(topic: String, data: ByteArray): ShopEvent {
        val record = avro.deserialize(topic, data) as GenericRecord
        return Avro.decodeFromGenericData<ShopEventRecord>(record.schema, record).event.toDomain()
    }

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
