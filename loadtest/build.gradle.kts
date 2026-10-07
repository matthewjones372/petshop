val proofloadVersion = "0.1.0-rc4"
val larkVersion: String = providers.gradleProperty("larkVersion").get()

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
    testImplementation("io.github.matthewjones372:pelican-test-wiremock:1.0.0-RC3")
    testImplementation(project(":registry"))
    // Pelican's typed client, pointed at a real server: the load names endpoints and never a URL,
    // which is also why `proofload-http` is not here.
    testImplementation("io.github.matthewjones372:pelican-test:1.0.0-RC3")
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

// Traffic for the demo, against the shop already running: `./gradlew :loadtest:demoTraffic`, with PETSHOP_URL,
// RATE and DURATION to change where, how many visitors a second, and for how long. See DemoTraffic.kt.
tasks.register<JavaExec>("demoTraffic") {
    description = "Sends visitors to a running shop, so the demo's dashboards have something to show."
    group = "demo"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("petshop.load.DemoTrafficKt")
}

// The same visitors as a program of their own, for the demo's compose file to run: the test classes as a jar and
// the classpath beside it, in build/demo-traffic. See demo/traffic/Dockerfile.
val demoTrafficJar = tasks.register<Jar>("demoTrafficJar") {
    archiveBaseName.set("demo-traffic")
    from(sourceSets.test.get().output)
}

tasks.register<Sync>("installDemoTraffic") {
    description = "Lays out DemoTraffic and its classpath in build/demo-traffic, for the demo's traffic container."
    group = "demo"
    from(demoTrafficJar)
    from(sourceSets.test.get().runtimeClasspath.filter { it.isFile })
    into(layout.buildDirectory.dir("demo-traffic/lib"))
}
