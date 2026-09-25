plugins {
    application
    // Checks every graph in this project on `check`, and renders each one.
    id("io.github.matthewjones372.lark.wiring") version "0.2.0"
}

application { mainClass.set("petshop.app.MainKt") }

// `-PlarkVersion=0.4.1-SNAPSHOT` builds against a lark installed with `publishToMavenLocal`.
val larkVersion: String = providers.gradleProperty("larkVersion").getOrElse("0.5.0")

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

    // A real HTTP server playing the registry, stubbed in the registry's own endpoints.
    testImplementation("io.github.matthewjones372:pelican-test-wiremock:1.0.0-RC3")
    testImplementation(project(":registry"))
}
