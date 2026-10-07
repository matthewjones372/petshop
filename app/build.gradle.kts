plugins {
    application
    `java-test-fixtures`
    // Checks every graph in this project on `check`, and renders each one.
    id("io.github.matthewjones372.lark.wiring") version "0.2.0"
    // The shop's events on the wire are Avro records, derived by avro4k from @Serializable classes.
    kotlin("plugin.serialization")
    // Domain events to wire records and back, derived at compile time: a field that cannot be mapped
    // does not compile.
    id("io.github.matthewjones372.kimney") version "0.3.0"
}

application { mainClass.set("petshop.app.MainKt") }

// With LOKI_URL set, the shop's lines also go to Loki. Picked by which file logback reads, so a run without it,
// and every test, never tries to reach a Loki that is not there.
tasks.named<JavaExec>("run") {
    providers.environmentVariable("LOKI_URL").orNull?.let { systemProperty("logback.configurationFile", "logback-loki.xml") }
}

val larkVersion: String = providers.gradleProperty("larkVersion").get()

dependencies {
    api(project(":api"))
    implementation(project(":registry"))
    implementation("io.github.matthewjones372:lark-app:$larkVersion")
    // The shop is an actor in a Lark flock, a node in the graph like any other.
    implementation("io.github.matthewjones372:lark-actor:$larkVersion")
    implementation("io.github.matthewjones372:lark-app-actor:$larkVersion")
    implementation("io.github.matthewjones372:lark-app-typesafe:$larkVersion")
    // Forks, which every stream runs on: the relay, the arrivals and the projection. Their descriptions
    // name no backend; the graph decides, and RelaySpec runs the relay on a clock the test moves.
    implementation("io.github.matthewjones372:lark-stream-forks:$larkVersion")
    // The bus on Kafka: a consumer loop on whichever backend the graph names, committing what it handled.
    implementation("io.github.matthewjones372:lark-kafka:$larkVersion")
    // Avro records carried in the schema registry's wire format, and derived from Kotlin classes: avro4k's
    // own serde for Confluent's registry, which asks for Confluent 8.3 or later.
    implementation("com.github.avro-kotlin.avro4k:avro4k-core:2.12.0")
    implementation("com.github.avro-kotlin.avro4k:avro4k-confluent-kafka-serializer:2.12.0")
    implementation("io.confluent:kafka-avro-serializer:8.3.0")
    // Confluent 8.3 asks for its own build of the Kafka 4.3 client, 8.3.0-ccs, which is not on Maven Central;
    // the Apache one it is built from stands in, so there is one Kafka client on the classpath. lark-kafka is
    // built against 3.8 and runs on it: KafkaBusSpec is the proof.
    implementation("org.apache.kafka:kafka-clients") { version { strictly("4.3.0") } }
    implementation("io.github.matthewjones372:lark-otel:$larkVersion")
    implementation("io.opentelemetry:opentelemetry-sdk:1.51.0")

    // The outbox is a Postgres table, and every statement against it but the schema is ExoQuery, in
    // a build of its own on the Kotlin ExoQuery's plugin is built for.
    implementation("petshop:outbox-table")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")

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
    // The shop's lines to Loki, for the demo's Estate; only read when LOKI_URL is set, see logback-loki.xml.
    runtimeOnly("com.github.loki4j:loki-logback-appender:2.0.3")

    // A claim about a backend is worth having only against the real one.
    testImplementation("ch.qos.logback:logback-classic:1.5.20")

    // A real Postgres for every test that builds the shop, and for the load test, which builds all of it.
    testFixturesApi("org.testcontainers:testcontainers-postgresql:2.0.5")
    testFixturesImplementation("io.github.matthewjones372:lark-app:$larkVersion")
    testFixturesImplementation("org.postgresql:postgresql:42.7.13")

    // The relay on time the test owns: an interval of ticks is one clock.adjust, and nothing sleeps.
    testImplementation("io.github.matthewjones372:lark-stream-test:$larkVersion")

    // Tests that read as stories: Given, When, Then, and a failure that says which step broke.
    testImplementation("io.github.matthewjones372:lark-test:$larkVersion")

    // A Kafka broker in a container, started once for the whole run, as the Postgres is.
    testImplementation("org.testcontainers:testcontainers-kafka:2.0.5")

    // A real HTTP server playing the registry, stubbed in the registry's own endpoints.
    testImplementation("io.github.matthewjones372:pelican-test-wiremock:1.0.0-RC3")
    testImplementation(project(":registry"))

    // The shop's own typed client, for the end-to-end test: it calls the service by endpoint, not URL.
    testImplementation("io.github.matthewjones372:pelican-test:1.0.0-RC3")
}

// lark-test's stories colour their console copy under FORCE_COLOR or -Plark.test.colour, and a test worker does
// not see the shell's environment, so these are handed on. Lark spec 0118 has its wiring plugin do this instead.
tasks.test {
    listOf("FORCE_COLOR", "NO_COLOR").forEach { name -> providers.environmentVariable(name).orNull?.let { environment(name, it) } }
    providers.gradleProperty("lark.test.colour").orNull?.let { systemProperty("lark.test.colour", it) }
}
