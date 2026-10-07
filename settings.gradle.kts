pluginManagement {
    // The wiring plugin ships from the same build as the library, at the same version, so it is pinned here to
    // larkVersion rather than in a build script, where it would drift.
    val larkVersion = providers.gradleProperty("larkVersion").get()
    repositories {
        mavenCentral()
        gradlePluginPortal()
        if (larkVersion.endsWith("-SNAPSHOT")) {
            maven("https://central.sonatype.com/repository/maven-snapshots/")
            mavenLocal()
        }
    }
    plugins {
        id("io.github.matthewjones372.lark.wiring") version larkVersion
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "petshop"

include("domain")
include("registry")
include("api")
include("app")
include("loadtest")

// The outbox's SQL, on the Kotlin its ExoQuery plugin is built for: see its settings.gradle.kts.
includeBuild("outbox-table")
