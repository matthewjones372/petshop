plugins {
    application
    // Checks every graph in this project on `check`, and renders each one.
    id("io.github.matthewjones372.lark.wiring") version "0.2.0"
}

application { mainClass.set("petshop.app.MainKt") }

val larkVersion = "0.2.0"

dependencies {
    api(project(":api"))
    implementation("io.github.matthewjones372:lark-app:$larkVersion")
    implementation("io.github.matthewjones372:lark-app-pekko:$larkVersion")
    implementation("io.github.matthewjones372:lark-app-typesafe:$larkVersion")
    implementation("io.github.matthewjones372:lark-stream:$larkVersion")
    implementation("io.github.matthewjones372:lark-pekko:$larkVersion")
    implementation("io.github.matthewjones372:lark-otel:$larkVersion")
    implementation("io.opentelemetry:opentelemetry-sdk:1.51.0")

    // The service's own lines go here too, through Slf4jLogger.
    implementation("org.slf4j:slf4j-api:2.0.17")

    // So a failure in a handler reaches a terminal rather than an SLF4J no-op.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.20")

    // A claim about a backend is worth having only against the real one.
    testImplementation("ch.qos.logback:logback-classic:1.5.20")
}
