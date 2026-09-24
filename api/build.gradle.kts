plugins {
    // Writes the domain → DTO crossings in Dtos.kt, and refuses to compile one it cannot.
    id("io.github.matthewjones372.kimney") version "0.3.0"
}

val pelicanVersion = "1.0.0-RC1"
val pekkoVersion = "1.2.1"

dependencies {
    api(project(":domain"))
    api("io.github.matthewjones372:pelican-pekko:$pelicanVersion")
    api("io.github.matthewjones372:pelican-jackson:$pelicanVersion")
    api("io.github.matthewjones372:pelican-pekko-docs:$pelicanVersion")

    // Pelican ships no Scala cross-build, so the Pekko that runs is named here.
    api(platform("org.apache.pekko:pekko-bom_2.13:$pekkoVersion"))
    api("org.apache.pekko:pekko-actor-typed_2.13")
    api("org.apache.pekko:pekko-stream_2.13")
    api("org.apache.pekko:pekko-http_2.13:1.3.0")

    testImplementation("io.github.matthewjones372:pelican-test:$pelicanVersion")
    testImplementation("io.github.matthewjones372:pelican-test-pekko:$pelicanVersion")
}
