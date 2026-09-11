val proofloadVersion = "0.1.0-rc4"

dependencies {
    testImplementation(project(":app"))
    testImplementation("io.github.matthewjones372:lark-app:0.1.2")
    // Pelican's typed client, pointed at a real server: the load names endpoints and never a URL,
    // which is also why `proofload-http` is not here.
    testImplementation("io.github.matthewjones372:pelican-test:1.0.0-RC1")
    testImplementation("io.github.matthewjones372:proofload-junit5:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-report-html:$proofloadVersion")
}
