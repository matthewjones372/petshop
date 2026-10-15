plugins {
    kotlin("plugin.serialization")
    id("io.github.matthewjones372.kimney") version "0.3.0"
}

val larkVersion: String = providers.gradleProperty("larkVersion").get()

dependencies {
    // api, so a module that depends on this one resolves the pelican modules named below without versions.
    api(platform("io.github.matthewjones372:pelican-bom:${providers.gradleProperty("pelicanVersion").get()}"))
    api(project(":domain"))
    api("io.github.matthewjones372:pelican-jetty")
    api("io.github.matthewjones372:pelican-jetty-docs")
    api("io.github.matthewjones372:pelican-kotlinx")
    api("io.github.matthewjones372:pelican-health")
    // A span per request, continuing an inbound traceparent.
    api("io.github.matthewjones372:pelican-metrics-otel")
    api("io.github.matthewjones372:lark-stream:$larkVersion")

    testImplementation("io.github.matthewjones372:lark-stream-forks:$larkVersion")
    testImplementation("io.github.matthewjones372:pelican-test")
    testImplementation("io.github.matthewjones372:pelican-test-jetty")
    testImplementation("io.github.matthewjones372:pelican-test-golden")
}
