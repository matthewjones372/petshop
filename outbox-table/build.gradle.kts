plugins {
    kotlin("jvm") version "2.3.0"
    // ExoQuery reads each `sql { }` at compile time and writes the SQL then, and it reads the row's
    // columns through kotlinx.serialization.
    kotlin("plugin.serialization") version "2.3.0"
    id("io.exoquery.exoquery-plugin") version "2.3.0-2.0.4.PL"
}

// The coordinates the petshop asks for, which Gradle substitutes with this build.
group = "petshop"

repositories { mavenCentral() }

dependencies {
    implementation("io.exoquery:exoquery-runner-jdbc:2.0.4.PL")
}

kotlin { jvmToolchain(21) }

// The ExoQuery plugin brings lz4 1.3.0 under its old coordinates, which has open advisories and no
// fixed release there; the project carried on as at.yawk.lz4:lz4-java, with the same packages.
// Kotlin stays at 2.3.0 for now: ExoQuery has no plugin built for a later compiler yet.
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("net.jpountz.lz4:lz4")).using(module("at.yawk.lz4:lz4-java:1.11.4"))
    }
}
