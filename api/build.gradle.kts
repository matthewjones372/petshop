val pelicanVersion = "1.0.0-RC3"
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
    testImplementation("io.github.matthewjones372:pelican-test-golden:$pelicanVersion")
}
