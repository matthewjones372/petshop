plugins {
    application
    // Checks every graph in this project on `check`, and renders each one.
    id("io.github.matthewjones372.lark.wiring") version "0.1.2"
}

application { mainClass.set("petshop.app.MainKt") }

val larkVersion = "0.1.2"

dependencies {
    api(project(":api"))
    implementation("io.github.matthewjones372:lark-app:$larkVersion")
    implementation("io.github.matthewjones372:lark-app-pekko:$larkVersion")
    implementation("io.github.matthewjones372:lark-app-typesafe:$larkVersion")
    implementation("io.github.matthewjones372:lark-stream:$larkVersion")
    implementation("io.github.matthewjones372:lark-pekko:$larkVersion")
    implementation("io.github.matthewjones372:lark-otel:$larkVersion")
    implementation("io.opentelemetry:opentelemetry-sdk:1.51.0")

    // So a failure in a handler reaches a terminal rather than an SLF4J no-op.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.20")
}
