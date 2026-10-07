pluginManagement {
    // Pelican's client generator ships from the same build as the library, at the same version.
    val pelicanVersion = providers.gradleProperty("pelicanVersion").get()
    repositories {
        // The wiring plugin ships from the same build as the library, and from
        // the same place.
        mavenCentral()
        gradlePluginPortal()
        if (pelicanVersion.endsWith("-SNAPSHOT")) {
            maven("https://central.sonatype.com/repository/maven-snapshots/")
            mavenLocal()
        }
    }
    plugins { id("io.github.matthewjones372.pelican") version pelicanVersion }
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
