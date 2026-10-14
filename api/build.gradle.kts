plugins {
    id("io.github.matthewjones372.kimney") version "0.3.0"
}

val pelicanVersion: String = providers.gradleProperty("pelicanVersion").get()
val larkVersion: String = providers.gradleProperty("larkVersion").get()
val pekkoVersion = "1.2.1"

dependencies {
    api(project(":domain"))
    api("io.github.matthewjones372:pelican-pekko:$pelicanVersion")
    api("io.github.matthewjones372:pelican-jackson:$pelicanVersion")
    api("io.github.matthewjones372:pelican-pekko-docs:$pelicanVersion")
    api("io.github.matthewjones372:pelican-health:$pelicanVersion")
    // A span per request, continuing an inbound traceparent.
    api("io.github.matthewjones372:pelican-metrics-otel:$pelicanVersion")
    api("io.github.matthewjones372:lark-stream:$larkVersion")
    // /events turns the feed's lark stream into the Pekko source Pelican's SSE endpoint answers with.
    implementation("io.github.matthewjones372:lark-stream-pekko:$larkVersion")

    // Pelican ships no Scala cross-build, so the Pekko that runs is named here.
    api(platform("org.apache.pekko:pekko-bom_2.13:$pekkoVersion"))
    api("org.apache.pekko:pekko-actor-typed_2.13")
    api("org.apache.pekko:pekko-stream_2.13")
    api("org.apache.pekko:pekko-http_2.13:1.3.0")

    testImplementation("io.github.matthewjones372:pelican-test:$pelicanVersion")
    testImplementation("io.github.matthewjones372:pelican-test-pekko:$pelicanVersion")
    testImplementation("io.github.matthewjones372:pelican-test-golden:$pelicanVersion")
}
