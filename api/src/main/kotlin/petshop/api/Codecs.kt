package petshop.api

import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.health.CheckResult
import io.github.matthewjones372.pelican.health.HealthReport
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * KotlinxCodecs, with pelican-health's report read through a `@Serializable` copy: HealthReport is a plain data
 * class, which kotlinx refuses when the API is assembled. The copy's serial names keep the components Jackson's.
 */
object KotlinxShopCodecs : Codecs by KotlinxCodecs {

    @Suppress("UNCHECKED_CAST")
    override fun <T> codec(type: KType): BodyCodec<T> =
        if (type.classifier == HealthReport::class) health as BodyCodec<T> else KotlinxCodecs.codec(type)

    // Delegated, this would call KotlinxCodecs' own codec(type) and miss the one above.
    override fun <T> codec(type: KType, mediaType: String): BodyCodec<T> =
        if (type.classifier == HealthReport::class) codec(type) else KotlinxCodecs.codec(type, mediaType)

    override fun schema(type: KType, components: SchemaComponents): JsonObj =
        KotlinxCodecs.schema(if (type.classifier == HealthReport::class) typeOf<Report>() else type, components)

    private val health = object : BodyCodec<HealthReport> {
        private val report = KotlinxCodecs.codec<Report>(typeOf<Report>())

        override fun encodeToString(value: HealthReport): String = report.encodeToString(value.copied())

        override fun decodeFromString(text: String): HealthReport = report.decodeFromString(text).original()

        override fun decodeFrom(bytes: ByteArray): HealthReport = report.decodeFrom(bytes).original()
    }

    @Serializable
    @SerialName("HealthReport")
    private data class Report(val status: String, val checks: Map<String, List<Result>>)

    @Serializable
    @SerialName("CheckResult")
    private data class Result(
        val status: String,
        val observedValue: Long,
        val observedUnit: String,
        val time: String,
        val componentType: String? = null,
        val output: String? = null,
    )

    private fun HealthReport.copied() = Report(status, checks.mapValues { (_, results) ->
        results.map { Result(it.status, it.observedValue, it.observedUnit, it.time, it.componentType, it.output) }
    })

    private fun Report.original() = HealthReport(status, checks.mapValues { (_, results) ->
        results.map { CheckResult(it.status, it.observedValue, it.observedUnit, it.time, it.componentType, it.output) }
    })
}
