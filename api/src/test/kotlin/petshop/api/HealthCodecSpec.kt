package petshop.api

import io.github.matthewjones372.pelican.health.CheckResult
import io.github.matthewjones372.pelican.health.HealthReport
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf

class HealthCodecSpec {

    private val report = HealthReport(
        "fail",
        mapOf(
            "database" to listOf(CheckResult("pass", 3, "ms", "2026-10-19T08:00:00Z", "datastore", null)),
            "registry" to listOf(CheckResult("fail", 2000, "ms", "2026-10-19T08:00:01Z", null, "timed out")),
        ),
    )

    private val codec = ShopCodecs.codec<HealthReport>(typeOf<HealthReport>())

    @Test
    fun `a health report reads back as the report that was written`() {
        val json = codec.encodeToString(report)

        codec.decodeFromString(json) shouldBe report
        codec.decodeFrom(json.toByteArray()) shouldBe report
    }

    @Test
    fun `a health report is written with pelican-health's field names`() {
        codec.encodeToString(report) shouldBe
            """{"status":"fail","checks":{"database":[{"status":"pass","observedValue":3,"observedUnit":"ms",""" +
            """"time":"2026-10-19T08:00:00Z","componentType":"datastore"}],"registry":[{"status":"fail",""" +
            """"observedValue":2000,"observedUnit":"ms","time":"2026-10-19T08:00:01Z","output":"timed out"}]}}"""
    }

    @Test
    fun `the json media type serves the same health codec`() {
        val typed = ShopCodecs.codec<HealthReport>(typeOf<HealthReport>(), "application/json")

        typed.decodeFromString(codec.encodeToString(report)) shouldBe report
    }
}
