plugins {
    // Checks on every build that the committed client still matches `registrySpec()`.
    id("io.github.matthewjones372.pelican") version "1.0.0"
}

val pelicanVersion: String = providers.gradleProperty("pelicanVersion").get()

// Only the generating task needs the generator, so it is on that classpath alone.
val codegen: Configuration = configurations.create("codegen")

dependencies {
    api("io.github.matthewjones372:pelican-core:$pelicanVersion")
    api("io.github.matthewjones372:pelican-jackson:$pelicanVersion")
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
            outputDir.set(layout.projectDirectory.dir("src/main/generated"))
            classpath.setFrom(sourceSets.named("main").map { it.runtimeClasspath }, codegen)
        }
    }
}
