pluginManagement {
    repositories {
        // The wiring plugin ships from the same build as the library, so a local
        // install covers it the same way `mavenLocal()` covers lark itself.
        mavenLocal()
        gradlePluginPortal()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "petshop"

include("domain")
include("api")
include("app")
include("loadtest")
