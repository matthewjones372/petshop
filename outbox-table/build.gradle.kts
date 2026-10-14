plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("io.exoquery.exoquery-plugin") version "2.3.0-2.0.4.PL"
}

// Gradle substitutes this build for the petshop:outbox-table dependency.
group = "petshop"

repositories { mavenCentral() }

dependencies {
    implementation("io.exoquery:exoquery-runner-jdbc:2.0.4.PL")
}

kotlin { jvmToolchain(21) }

// ExoQuery brings net.jpountz lz4 1.3.0, which has open advisories and no fix under those coordinates;
// at.yawk.lz4:lz4-java is its continuation with the same packages.
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("net.jpountz.lz4:lz4")).using(module("at.yawk.lz4:lz4-java:1.11.4"))
    }
}
