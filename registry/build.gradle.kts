plugins {
    // Checks on every build that the committed client still matches `registrySpec()`.
    id("io.github.matthewjones372.pelican") version "1.0.0"
    kotlin("plugin.serialization")
}

val pelicanVersion: String = providers.gradleProperty("pelicanVersion").get()

// Only the generating task needs the generator, so it is on that classpath alone.
val codegen: Configuration = configurations.create("codegen")

dependencies {
    // api, so a module that depends on this one resolves the pelican modules named below without versions.
    api(platform("io.github.matthewjones372:pelican-bom:${providers.gradleProperty("pelicanVersion").get()}"))
    api("io.github.matthewjones372:pelican-core")
    // The generated types are @Serializable data classes, which either codec reads.
    api("io.github.matthewjones372:pelican-kotlinx")
    api("io.github.matthewjones372:pelican-jackson")
    codegen("io.github.matthewjones372:pelican-codegen:$pelicanVersion")
    codegen(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
}

kotlin.sourceSets.named("main") { kotlin.srcDir("src/main/generated") }

// Pelican's check loads `registrySpec()` in Gradle's JVM, so this module targets no newer JDK than 25.

pelican {
    clients {
        create("registry") {
            specClass.set("petshop.registry.RegistryKt")
            specFunction.set("registrySpec")
            packageName.set("petshop.registry.client")
            codec.set("kotlinx")
            outputDir.set(layout.projectDirectory.dir("src/main/generated"))
            classpath.setFrom(sourceSets.named("main").map { it.runtimeClasspath }, codegen)
        }
    }
}
