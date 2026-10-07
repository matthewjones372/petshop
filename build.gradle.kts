plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
        // Confluent's Avro serializer and schema-registry client, which are not on Maven Central.
        maven("https://packages.confluent.io/maven/") { content { includeGroup("io.confluent") } }
        // A lark or pelican change is tried here before it is released, as a snapshot: published to Central's
        // snapshot repository, or installed locally.
        if (listOf("larkVersion", "pelicanVersion").any { providers.gradleProperty(it).get().endsWith("-SNAPSHOT") }) {
            maven("https://central.sonatype.com/repository/maven-snapshots/")
            mavenLocal()
        }
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
        "testImplementation"("io.kotest:kotest-assertions-core:6.2.4")
        // shouldBeRight and shouldBeLeft, so a test asserts on an Either rather than unwrapping one.
        "testImplementation"("io.kotest:kotest-assertions-arrow:6.2.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> { useJUnitPlatform() }
}
