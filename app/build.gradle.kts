plugins {
    application
    // Checks every graph in this project on `check`, and renders each one.
    id("io.github.matthewjones372.lark.wiring") version "0.2.0"
    // The shop's events on the wire are Avro records, derived by avro4k from @Serializable classes.
    kotlin("plugin.serialization")
    // Domain events to wire records and back, derived at compile time: a field that cannot be mapped
    // does not compile.
    id("io.github.matthewjones372.kimney") version "0.3.0"
}

application { mainClass.set("petshop.app.MainKt") }

val larkVersion: String = providers.gradleProperty("larkVersion").get()

dependencies {
    api(project(":api"))
    implementation(project(":registry"))
    implementation("io.github.matthewjones372:lark-app:$larkVersion")
    implementation("io.github.matthewjones372:lark-app-pekko:$larkVersion")
    implementation("io.github.matthewjones372:lark-app-typesafe:$larkVersion")
    // The Pekko backend, which brings lark-stream with it. The relay's description names no backend:
    // the graph decides, and RelaySpec runs the same description on a clock the test moves.
    implementation("io.github.matthewjones372:lark-stream-pekko:$larkVersion")
    implementation("io.github.matthewjones372:lark-pekko:$larkVersion")
    // The bus on Kafka: a consumer loop on whichever backend the graph names, committing what it handled.
    implementation("io.github.matthewjones372:lark-kafka:$larkVersion")
    // Avro records carried in the schema registry's wire format, and derived from Kotlin classes.
    // 7.8 is built on Kafka 3.8, the client lark-kafka is.
    implementation("io.confluent:kafka-avro-serializer:7.8.0")
    // Confluent 7.8 asks for its own build of the 3.8 client, 7.8.0-ccs; the Apache one it is built from is
    // the one lark-kafka is built against, so there is one Kafka client on the classpath.
    implementation("org.apache.kafka:kafka-clients") { version { strictly("3.8.0") } }
    implementation("com.github.avro-kotlin.avro4k:avro4k-core:2.12.0")
    implementation("io.github.matthewjones372:lark-otel:$larkVersion")
    implementation("io.opentelemetry:opentelemetry-sdk:1.51.0")

    // The shop's client for the chip registry sends through Pekko HTTP, on the system it already runs.
    implementation("io.github.matthewjones372:pelican-client-pekko:1.0.0-RC3")

    // On the classpath and nothing else: each registers itself through a
    // ServiceLoader, so the service's own lines and numbers go where its
    // libraries' already do, with nothing to remember in main.
    implementation("io.github.matthewjones372:lark-slf4j:$larkVersion")
    implementation("io.github.matthewjones372:lark-micrometer:$larkVersion")

    // The registry that answers /metrics. Micrometer's global composite is what
    // lark-micrometer writes to, and this is what is added to it.
    implementation("io.micrometer:micrometer-registry-prometheus:1.12.0")

    // So a failure in a handler reaches a terminal rather than an SLF4J no-op.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.20")

    // A claim about a backend is worth having only against the real one.
    testImplementation("ch.qos.logback:logback-classic:1.5.20")

    // The relay on time the test owns: an interval of ticks is one clock.adjust, and nothing sleeps.
    testImplementation("io.github.matthewjones372:lark-stream-test:$larkVersion")

    // The same Kafka bus on the other backend, and a broker in the test JVM so the suite needs no Docker.
    testImplementation("io.github.matthewjones372:lark-stream-forks:$larkVersion")
    testImplementation("io.github.embeddedkafka:embedded-kafka_2.13:3.8.0")

    // A real HTTP server playing the registry, stubbed in the registry's own endpoints.
    testImplementation("io.github.matthewjones372:pelican-test-wiremock:1.0.0-RC3")
    testImplementation(project(":registry"))
}
