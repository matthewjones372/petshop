// A snapshot until a release carries spec 0143, so a day of demo traffic holds what an hour does.
val proofloadVersion = "0.1.1-SNAPSHOT"
val surefoldVersion: String = providers.gradleProperty("surefoldVersion").get()

dependencies {
    testImplementation(project(":app"))
    testImplementation(testFixtures(project(":app")))
    testImplementation("io.github.matthewjones372:surefold-app:$surefoldVersion")
    testImplementation("io.github.matthewjones372:surefold-app-typesafe:$surefoldVersion")
    testImplementation("io.github.matthewjones372:surefold-stream:$surefoldVersion")
    testImplementation("io.github.matthewjones372:surefold-test:$surefoldVersion")
    testImplementation("io.github.matthewjones372:pelican-test-wiremock")
    testImplementation(project(":registry"))
    testImplementation("io.github.matthewjones372:pelican-test")
    testImplementation("io.github.matthewjones372:proofload-junit5:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-report-html:$proofloadVersion")
    // For Jetty's HttpClient, at the version pelican's BOM picks.
    testImplementation("io.github.matthewjones372:pelican-client-jetty")
}

// Only run as `./gradlew loadTest`: each test busies the machine for ten seconds and measures it.
tasks.test { enabled = false }

tasks.register<Test>("loadTest") {
    description = "Runs the load tests against the whole service, started in-process."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // Coverage reports depend on every Test task, so this runs only when `loadTest` is asked for by name.
    onlyIf { gradle.startParameter.taskNames.any { it == "loadTest" || it.endsWith(":loadTest") } }
    // A cached pass says nothing about this machine now.
    outputs.upToDateWhen { false }
    // Gradle's 512 MB default runs out under a rung the shop cannot keep up with; exit rather than hang when it does.
    maxHeapSize = "4g"
    jvmArgs("-XX:+ExitOnOutOfMemoryError")
}

// Against a running shop; PETSHOP_URL, RATE (visitors a second) and DURATION configure it.
tasks.register<JavaExec>("demoTraffic") {
    description = "Sends visitors to a running shop, so the demo's dashboards have something to show."
    group = "demo"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("petshop.load.DemoTrafficKt")
}

// Against a running shop, best through loadtest/heavy.sh; PETSHOP_URL, PEAK, RAMP, HOLD, PETS and TIMEOUT configure it.
tasks.register<JavaExec>("heavyTraffic") {
    description = "Ramps a running shop to fifty thousand requests a second and holds it there."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("petshop.load.HeavyTrafficKt")
    // Tens of thousands of requests in flight once the shop falls behind; exit rather than hang when they do not fit.
    maxHeapSize = "4g"
    jvmArgs("-XX:+ExitOnOutOfMemoryError")
}

// For demo/traffic/Dockerfile.
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
