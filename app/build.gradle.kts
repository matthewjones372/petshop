plugins {
    application
    `java-test-fixtures`
    id("io.github.matthewjones372.lark.wiring")
    kotlin("plugin.serialization")
    id("io.github.matthewjones372.kimney") version "0.3.0"
}

application { mainClass.set("petshop.app.MainKt") }

// LOKI_URL picks logback-loki.xml, so a run without it, and every test, never tries to reach Loki.
tasks.named<JavaExec>("run") {
    providers.environmentVariable("LOKI_URL").orNull?.let { systemProperty("logback.configurationFile", "logback-loki.xml") }
}

val larkVersion: String = providers.gradleProperty("larkVersion").get()
val pelicanVersion: String = providers.gradleProperty("pelicanVersion").get()

dependencies {
    api(project(":api"))
    implementation(project(":registry"))
    implementation("io.github.matthewjones372:lark-app:$larkVersion")
    implementation("io.github.matthewjones372:lark-actor:$larkVersion")
    implementation("io.github.matthewjones372:lark-app-actor:$larkVersion")
    implementation("io.github.matthewjones372:lark-app-typesafe:$larkVersion")
    implementation("io.github.matthewjones372:lark-app-liquibase:$larkVersion")
    implementation("io.github.matthewjones372:lark-stream-forks:$larkVersion")
    implementation("io.github.matthewjones372:lark-kafka:$larkVersion")
    // avro4k's Confluent serde needs Confluent 8.3 or later.
    implementation("com.github.avro-kotlin.avro4k:avro4k-core:2.12.0")
    implementation("com.github.avro-kotlin.avro4k:avro4k-confluent-kafka-serializer:2.12.0")
    implementation("io.confluent:kafka-avro-serializer:8.3.0")
    // Confluent 8.3 wants Kafka 8.3.0-ccs, not on Maven Central; the Apache 4.3 client stands in so there is
    // one Kafka client on the classpath. lark-kafka is built against 3.8 and runs on it.
    implementation("org.apache.kafka:kafka-clients") { version { strictly("4.3.0") } }
    // Past open advisories: lz4-java 1.10.2 under kafka-clients, and HttpClient 5.5 and HttpCore 5.3.4
    // under Confluent's schema-registry client.
    constraints {
        implementation("at.yawk.lz4:lz4-java:1.11.4")
        implementation("org.apache.httpcomponents.client5:httpclient5:5.6.3")
        implementation("org.apache.httpcomponents.core5:httpcore5:5.4.3")
        implementation("org.apache.httpcomponents.core5:httpcore5-h2:5.4.3")
    }
    implementation("io.github.matthewjones372:lark-otel:$larkVersion")
    // One OpenTelemetry version for the API pelican brings and the SDK here: a mixed pair fails at start.
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.67.0"))
    implementation("io.opentelemetry:opentelemetry-sdk")
    // OTEL_EXPORTER_OTLP_ENDPOINT turns span export on; see Wiring.kt.
    implementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    implementation("petshop:outbox-table")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")

    implementation("io.github.matthewjones372:pelican-client-pekko:$pelicanVersion")

    // Each registers itself through ServiceLoader, so being on the classpath is enough.
    implementation("io.github.matthewjones372:lark-slf4j:$larkVersion")
    implementation("io.github.matthewjones372:lark-micrometer:$larkVersion")

    // The simpleclient build keeps the 1.12 metric names Estate queries; 1.12's micrometer-core has an
    // open advisory.
    implementation("io.micrometer:micrometer-registry-prometheus-simpleclient:1.16.6")

    // Without a backend, SLF4J is a no-op and a failure in a handler reaches no terminal.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.34")
    runtimeOnly("com.github.loki4j:loki-logback-appender:2.0.3")

    testImplementation("ch.qos.logback:logback-classic:1.5.34")

    testFixturesApi("org.testcontainers:testcontainers-postgresql:2.0.5")
    testFixturesImplementation("io.github.matthewjones372:lark-app:$larkVersion")
    testFixturesImplementation("io.github.matthewjones372:lark-app-typesafe:$larkVersion")
    testFixturesImplementation("org.postgresql:postgresql:42.7.13")
    testFixturesImplementation(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))

    testImplementation("io.github.matthewjones372:lark-stream-test:$larkVersion")

    testImplementation("io.github.matthewjones372:lark-test:$larkVersion")

    testImplementation("org.testcontainers:testcontainers-kafka:2.0.5")

    testImplementation("io.github.matthewjones372:pelican-test-wiremock:$pelicanVersion")
    testImplementation(project(":registry"))

    testImplementation("io.github.matthewjones372:pelican-test:$pelicanVersion")
}
