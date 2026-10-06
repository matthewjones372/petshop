val proofloadVersion = "0.1.0-rc4"
val larkVersion: String = providers.gradleProperty("larkVersion").get()
val pelicanVersion: String = providers.gradleProperty("pelicanVersion").get()

dependencies {
    testImplementation(project(":app"))
    // The shop keeps its outbox in Postgres, so the load test starts one.
    testImplementation(testFixtures(project(":app")))
    testImplementation("io.github.matthewjones372:lark-app:$larkVersion")
    // A second instance is the same graph on another port, which is one line of configuration.
    testImplementation("io.github.matthewjones372:lark-app-typesafe:$larkVersion")
    // Stopping an instance's arrivals, so the relays can finish what was recorded.
    testImplementation("io.github.matthewjones372:lark-stream:$larkVersion")
    // The chip registry the shop calls out to, played by a real HTTP server.
    testImplementation("io.github.matthewjones372:pelican-test-wiremock:$pelicanVersion")
    testImplementation(project(":registry"))
    // Pelican's typed client, pointed at a real server: the load names endpoints and never a URL,
    // which is also why `proofload-http` is not here.
    testImplementation("io.github.matthewjones372:pelican-test:$pelicanVersion")
    testImplementation("io.github.matthewjones372:proofload-junit5:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-report-html:$proofloadVersion")
}

// The load tests run when asked, as `./gradlew loadTest`, and not with every `test` or `build`: each holds the
// machine busy for ten seconds and measures it, so they belong to a quiet machine rather than to every build.
tasks.test { enabled = false }

tasks.register<Test>("loadTest") {
    description = "Runs the load tests against the whole service, started in-process."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // A measurement, not a build output: a cached pass says nothing about this machine now.
    outputs.upToDateWhen { false }
}
