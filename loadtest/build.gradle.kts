val proofloadVersion = "0.1.0-rc4"
val larkVersion: String = providers.gradleProperty("larkVersion").get()
val pelicanVersion: String = providers.gradleProperty("pelicanVersion").get()

dependencies {
    testImplementation(project(":app"))
    testImplementation(testFixtures(project(":app")))
    testImplementation("io.github.matthewjones372:lark-app:$larkVersion")
    testImplementation("io.github.matthewjones372:lark-app-typesafe:$larkVersion")
    testImplementation("io.github.matthewjones372:lark-stream:$larkVersion")
    testImplementation("io.github.matthewjones372:pelican-test-wiremock:$pelicanVersion")
    testImplementation(project(":registry"))
    testImplementation("io.github.matthewjones372:pelican-test:$pelicanVersion")
    testImplementation("io.github.matthewjones372:proofload-junit5:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-report-html:$proofloadVersion")
}

// Only run as `./gradlew loadTest`: each test busies the machine for ten seconds and measures it.
tasks.test { enabled = false }

tasks.register<Test>("loadTest") {
    description = "Runs the load tests against the whole service, started in-process."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // A cached pass says nothing about this machine now.
    outputs.upToDateWhen { false }
}

// Against a running shop; PETSHOP_URL, RATE (visitors a second) and DURATION configure it.
tasks.register<JavaExec>("demoTraffic") {
    description = "Sends visitors to a running shop, so the demo's dashboards have something to show."
    group = "demo"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("petshop.load.DemoTrafficKt")
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
