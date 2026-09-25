// A build of its own, and not a module of the petshop's, because ExoQuery's compiler plugin is built
// for Kotlin 2.3.0 and fails to load in 2.4.10, which the rest of the petshop is on. A build of its
// own has its own Kotlin Gradle plugin; a module does not. Once ExoQuery ships for 2.4, this folds
// back into `app`.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "outbox-table"
