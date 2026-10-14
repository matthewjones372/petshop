plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
        // Confluent's Avro serializer and schema-registry client, which are not on Maven Central.
        maven("https://packages.confluent.io/maven/") { content { includeGroup("io.confluent") } }
        // A lark or pelican change is tried here before it is released by installing it locally as a snapshot.
        if (listOf("larkVersion", "pelicanVersion").any { providers.gradleProperty(it).get().endsWith("-SNAPSHOT") }) {
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
        // jackson reaches every module through lark and pelican at 2.22.2, which has open advisories.
        "implementation"(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
    }

    tasks.withType<Test> { useJUnitPlatform() }
}
