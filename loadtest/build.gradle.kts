val proofloadVersion = "0.1.0-rc4"

dependencies {
    testImplementation(project(":app"))
    testImplementation("io.github.matthewjones372:lark-app:0.1.1-SNAPSHOT")
    testImplementation("io.github.matthewjones372:proofload-http:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-junit5:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-report-html:$proofloadVersion")
}
