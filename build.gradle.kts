plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
    // 2.0, because detekt 1.23 is built against Kotlin 2.0 and cannot read 2.4 sources.
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("info.solidsoft.pitest") version "1.19.0" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlinx.kover")
    apply(plugin = "dev.detekt")
    apply(plugin = "info.solidsoft.pitest")

    repositories {
        mavenCentral()
        // Confluent's artifacts are not on Maven Central.
        maven("https://packages.confluent.io/maven/") { content { includeGroup("io.confluent") } }
        if (listOf("larkVersion", "pelicanVersion").any { providers.gradleProperty(it).get().endsWith("-SNAPSHOT") }) {
            maven("https://central.sonatype.com/repository/maven-snapshots/")
            mavenLocal()
            // Gradle keeps a snapshot for 24h by default, so CI's cache compiled against a lark from before toSequence.
            configurations.configureEach { resolutionStrategy.cacheChangingModulesFor(0, "seconds") }
        } else {
            // Proofload's snapshot, for the load test: a day of demo traffic needs spec 0143, which no release has yet.
            maven("https://central.sonatype.com/repository/maven-snapshots/") {
                content { includeModuleByRegex("io\\.github\\.matthewjones372", "proofload-.*") }
            }
        }
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
        compilerOptions { allWarningsAsErrors.set(true) }
    }

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // Gauntlet's lint ratchet decides which findings block, so findings from before it don't fail `build`.
        ignoreFailures.set(true)
        // `-Pdetekt.autoCorrect=true` lets the ktlint rules rewrite the layout they report.
        autoCorrect.set(providers.gradleProperty("detekt.autoCorrect").map(String::toBoolean).orElse(false))
    }

    // registry's client is generated from its contract, so its layout is the generator's.
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach { exclude { it.file.path.contains("/src/main/generated/") } }

    extensions.configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
        pitestVersion.set("1.30.1")
        junit5PluginVersion.set("1.2.3")
    }

    dependencies {
        "detektPlugins"("dev.detekt:detekt-rules-ktlint-wrapper:2.0.0-alpha.6")
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
