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
