plugins {
    // Writes the client from `registrySpec()`, and checks on every build that the committed one is
    // still what the descriptions produce.
    id("io.github.matthewjones372.pelican")
}

val pelicanVersion: String = providers.gradleProperty("pelicanVersion").get()

// The generator runs off this module's classpath rather than shipping its own, and nothing the
// service runs needs it: so it is on the generating task's classpath and nowhere else.
val codegen: Configuration = configurations.create("codegen")

dependencies {
    api("io.github.matthewjones372:pelican-core:$pelicanVersion")
    api("io.github.matthewjones372:pelican-jackson:$pelicanVersion")
    codegen("io.github.matthewjones372:pelican-codegen:$pelicanVersion")
}

kotlin.sourceSets.named("main") { kotlin.srcDir("src/main/generated") }

// Pelican's check loads `registrySpec()` in Gradle's own JVM, not this module's toolchain, and Pelican is built for
// 25: so Gradle runs on 25 too, which gradle/gradle-daemon-jvm.properties asks for.

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
