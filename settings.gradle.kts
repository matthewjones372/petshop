pluginManagement {
    repositories {
        // The wiring plugin ships from the same build as the library, and from
        // the same place.
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "petshop"

include("domain")
include("registry")
include("api")
include("app")
include("loadtest")
