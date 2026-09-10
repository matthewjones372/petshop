plugins {
    kotlin("jvm") version "2.4.10" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        // lark 0.1.0 is still propagating to repo1; mavenLocal covers the gap and
        // is harmless once it lands.
        mavenLocal()
        mavenCentral()
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
        "testImplementation"("io.kotest:kotest-assertions-core:6.2.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> { useJUnitPlatform() }
}
