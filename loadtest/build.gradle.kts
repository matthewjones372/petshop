val proofloadVersion = "0.1.0-rc4"

dependencies {
    testImplementation(project(":app"))
    testImplementation("io.github.matthewjones372:lark-app:0.1.1-SNAPSHOT")
    // The typed client the contract tests use, pointed at a real server: the load test names an
    // endpoint and never a URL.
    testImplementation("io.github.matthewjones372:pelican-test:1.0.0-RC1")
    testImplementation("io.github.matthewjones372:proofload-http:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-junit5:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-report-html:$proofloadVersion")
}
