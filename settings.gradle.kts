pluginManagement {
    val surefoldVersion = providers.gradleProperty("surefoldVersion").get()
    val pelicanVersion = providers.gradleProperty("pelicanVersion").get()
    repositories {
        mavenCentral()
        gradlePluginPortal()
        if (listOf(surefoldVersion, pelicanVersion).any { it.endsWith("-SNAPSHOT") }) {
            maven("https://central.sonatype.com/repository/maven-snapshots/")
            mavenLocal()
        }
    }
    // Each plugin ships from the same build as its library, so it follows the library's version.
    plugins {
        id("io.github.matthewjones372.surefold.wiring") version surefoldVersion
        id("io.github.matthewjones372.pelican") version pelicanVersion
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "petshop"

include("domain")
include("registry")
include("api")
include("app")
include("loadtest")

// A separate build for ExoQuery's Kotlin version: see its settings.gradle.kts.
includeBuild("outbox-table")
