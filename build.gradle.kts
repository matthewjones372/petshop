plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
        // Confluent's artifacts are not on Maven Central.
        maven("https://packages.confluent.io/maven/") { content { includeGroup("io.confluent") } }
        if (listOf("larkVersion", "pelicanVersion").any { providers.gradleProperty(it).get().endsWith("-SNAPSHOT") }) {
            maven("https://central.sonatype.com/repository/maven-snapshots/")
            mavenLocal()
        } else {
            // Proofload's snapshot, for the load test: a day of demo traffic needs spec 0143, which no release has yet.
            maven("https://central.sonatype.com/repository/maven-snapshots/") {
                content { includeModuleByRegex("io\\.github\\.matthewjones372", "proofload-.*") }
            }
        }
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
        "testImplementation"("io.kotest:kotest-assertions-core:6.2.4")
        "testImplementation"("io.kotest:kotest-assertions-arrow:6.2.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        // pelican-core, Avro and Confluent bring Jackson before 2.22.3, which has open advisories.
        "implementation"(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
        // Every pelican module at pelicanVersion, so no build file names a pelican version of its own.
        "implementation"(platform("io.github.matthewjones372:pelican-bom:${providers.gradleProperty("pelicanVersion").get()}"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // `-Dpelican.golden.update=true` reaches the test JVM, which is where golden and stub files are rewritten.
        providers.systemProperty("pelican.golden.update").orNull?.let { systemProperty("pelican.golden.update", it) }
    }
}
