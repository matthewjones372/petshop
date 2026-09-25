val pelicanVersion = "1.0.0-RC1"

/*
 * WireMock, stubbed and verified in Pelican endpoints rather than URLs and JSON strings. A test
 * library, so everything it hands a test is `api`: the test names WireMock's faults and Pelican's
 * outcomes directly.
 */
dependencies {
    api("io.github.matthewjones372:pelican-core:$pelicanVersion")
    api("io.github.matthewjones372:pelican-jackson:$pelicanVersion")
    api("org.wiremock:wiremock-standalone:3.13.1")
    api("org.junit.jupiter:junit-jupiter-api:6.1.3")

    // Answers are rendered by the same code a Pelican server answers with, and the requests a stub
    // matches are built the way the typed test client builds them.
    implementation("io.github.matthewjones372:pelican-pekko:$pelicanVersion")
    implementation("io.github.matthewjones372:pelican-test-golden:$pelicanVersion")
    implementation(platform("org.apache.pekko:pekko-bom_2.13:1.2.1"))
    implementation("org.apache.pekko:pekko-actor-typed_2.13")
    implementation("org.apache.pekko:pekko-stream_2.13")
    implementation("org.apache.pekko:pekko-http_2.13:1.3.0")
}
