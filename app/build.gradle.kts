plugins {
    application
    `java-test-fixtures`
    id("io.github.matthewjones372.surefold.wiring")
    kotlin("plugin.serialization")
    id("io.github.matthewjones372.kimney") version "0.3.0"
}

application { mainClass.set("petshop.app.MainKt") }

// surefold-app warns when an incremental compile cannot see a graph's source, and warnings are errors.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") { incremental = false }

// LOKI_URL picks logback-loki.xml, so a run without it, and every test, never tries to reach Loki.
tasks.named<JavaExec>("run") {
    providers.environmentVariable("LOKI_URL").orNull?.let { systemProperty("logback.configurationFile", "logback-loki.xml") }
}

val surefoldVersion: String = providers.gradleProperty("surefoldVersion").get()

dependencies {
    api(project(":api"))
    implementation(project(":registry"))
    implementation("io.github.matthewjones372:surefold-app:$surefoldVersion")
    implementation("io.github.matthewjones372:surefold-actor:$surefoldVersion")
    implementation("io.github.matthewjones372:surefold-app-actor:$surefoldVersion")
    implementation("io.github.matthewjones372:surefold-app-typesafe:$surefoldVersion")
    implementation("io.github.matthewjones372:surefold-app-kotlinx:$surefoldVersion")
    implementation("io.github.matthewjones372:surefold-app-liquibase:$surefoldVersion")
    implementation("io.github.matthewjones372:surefold-stream-forks:$surefoldVersion")
    implementation("io.github.matthewjones372:surefold-kafka:$surefoldVersion")
    // avro4k's Confluent serde needs Confluent 8.3 or later.
    implementation("com.github.avro-kotlin.avro4k:avro4k-core:2.12.0")
    implementation("com.github.avro-kotlin.avro4k:avro4k-confluent-kafka-serializer:2.12.0")
    implementation("io.confluent:kafka-avro-serializer:8.3.0")
    // Confluent 8.3 wants Kafka 8.3.0-ccs, not on Maven Central; the Apache 4.3 client stands in so there is
    // one Kafka client on the classpath, the one surefold-kafka is built against.
    implementation("org.apache.kafka:kafka-clients") { version { strictly("4.3.0") } }
    // Past open advisories: lz4-java 1.10.2 under kafka-clients, and HttpClient 5.5 and HttpCore 5.3.4
    // under Confluent's schema-registry client.
    constraints {
        implementation("at.yawk.lz4:lz4-java:1.11.4")
        implementation("org.apache.httpcomponents.client5:httpclient5:5.6.3")
        implementation("org.apache.httpcomponents.core5:httpcore5:5.4.3")
        implementation("org.apache.httpcomponents.core5:httpcore5-h2:5.4.3")
        // Confluent's 32.0.1 calls sun.misc.Unsafe, which JDK 25 warns about at runtime; 33.7 uses VarHandle.
        implementation("com.google.guava:guava:33.7.2-jre")
    }
    implementation("io.github.matthewjones372:surefold-otel:$surefoldVersion")
    // One OpenTelemetry version for the API pelican brings and the SDK here: a mixed pair fails at start.
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.67.0"))
    implementation("io.opentelemetry:opentelemetry-sdk")
    // OTEL_EXPORTER_OTLP_ENDPOINT turns span export on; see Wiring.kt.
    implementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    implementation("petshop:outbox-table")
    // The outbox's metadata column, written as JSON.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")

    implementation("io.github.matthewjones372:pelican-client-jetty")

    // Each registers itself through ServiceLoader, so being on the classpath is enough.
    implementation("io.github.matthewjones372:surefold-slf4j:$surefoldVersion")
    implementation("io.github.matthewjones372:surefold-micrometer:$surefoldVersion")

    // On the Prometheus Java client 1.x; the simpleclient build it replaces is deprecated.
    implementation("io.micrometer:micrometer-registry-prometheus:1.16.6")

    // Without a backend, SLF4J is a no-op and a failure in a handler reaches no terminal.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.34")
    runtimeOnly("com.github.loki4j:loki-logback-appender:2.0.3")

    testImplementation("ch.qos.logback:logback-classic:1.5.34")

    testFixturesApi("org.testcontainers:testcontainers-postgresql:2.0.5")
    testFixturesImplementation("io.github.matthewjones372:surefold-app:$surefoldVersion")
    testFixturesImplementation("io.github.matthewjones372:surefold-app-typesafe:$surefoldVersion")
    testFixturesImplementation("org.postgresql:postgresql:42.7.13")
    testFixturesImplementation(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))

    testImplementation("io.github.matthewjones372:surefold-stream-test:$surefoldVersion")

    testImplementation("io.github.matthewjones372:surefold-test:$surefoldVersion")

    testImplementation("io.github.matthewjones372:surefold-kafka-test:$surefoldVersion")
    testImplementation("io.github.matthewjones372:surefold-otel-test:$surefoldVersion")

    testImplementation("io.github.matthewjones372:pelican-test-wiremock")
    testImplementation(project(":registry"))

    testImplementation("io.github.matthewjones372:pelican-test")
}

// The shop runs on Jetty with kotlinx: a dependency that brings Pekko, Scala or Jackson's databind back fails `check`.
val checkRuntimeClasspath = tasks.register("checkRuntimeClasspath") {
    description = "Fails if the shop's runtime classpath holds Pekko, Scala, pelican-jackson or jackson-module-kotlin."
    group = "verification"
    val forbidden = listOf("org.apache.pekko", "pekko-", "scala-library", "pelican-jackson", "jackson-module-kotlin")
    val entries = configurations.runtimeClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }
        .map { artifacts -> artifacts.map { "${it.file.name} ${it.id.componentIdentifier.displayName}" } }
    inputs.property("entries", entries)
    doLast {
        val refused = entries.get().filter { entry -> forbidden.any { entry.contains(it) } }
        if (refused.isNotEmpty()) throw GradleException("The runtime classpath has ${refused.joinToString()}")
    }
}
tasks.named("check") { dependsOn(checkRuntimeClasspath) }
