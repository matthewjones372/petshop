// A separate build because ExoQuery's compiler plugin needs Kotlin 2.3.0 and fails to load in 2.4.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Gradle will not download JDK 21 without this, and the root settings do not reach an included build.
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "outbox-table"
