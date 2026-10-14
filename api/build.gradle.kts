plugins {
    kotlin("plugin.serialization")
    id("io.github.matthewjones372.kimney") version "0.3.0"
}

val larkVersion: String = providers.gradleProperty("larkVersion").get()
val pekkoVersion = "1.2.1"

// Both servers and both codecs: petshop.stack and petshop.codec choose at start, so each is on the classpath.
dependencies {
    // api, so a module that depends on this one resolves the pelican modules named below without versions.
    api(platform("io.github.matthewjones372:pelican-bom:${providers.gradleProperty("pelicanVersion").get()}"))
    api(project(":domain"))
    api("io.github.matthewjones372:pelican-jetty")
    api("io.github.matthewjones372:pelican-jetty-docs")
    api("io.github.matthewjones372:pelican-pekko")
    api("io.github.matthewjones372:pelican-pekko-docs")
    api("io.github.matthewjones372:pelican-jackson")
    api("io.github.matthewjones372:pelican-kotlinx")
    api("io.github.matthewjones372:pelican-health")
    // A span per request, continuing an inbound traceparent.
    api("io.github.matthewjones372:pelican-metrics-otel")
    api("io.github.matthewjones372:lark-stream:$larkVersion")
    // On Pekko, /events turns the feed's lark stream into the Pekko source Pelican's SSE endpoint answers with.
    api("io.github.matthewjones372:lark-stream-pekko:$larkVersion")

    // Pelican ships no Scala cross-build, so the Pekko that runs is named here.
    api(platform("org.apache.pekko:pekko-bom_2.13:$pekkoVersion"))
    api("org.apache.pekko:pekko-actor-typed_2.13")
    api("org.apache.pekko:pekko-stream_2.13")
    api("org.apache.pekko:pekko-http_2.13:1.3.0")

    testImplementation("io.github.matthewjones372:lark-stream-forks:$larkVersion")
    testImplementation("io.github.matthewjones372:pelican-test")
    testImplementation("io.github.matthewjones372:pelican-test-jetty")
    testImplementation("io.github.matthewjones372:pelican-test-golden")
}
