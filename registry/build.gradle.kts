plugins {
    // Writes the client from `registrySpec()`, and checks on every build that the committed one is
    // still what the descriptions produce.
    id("io.github.matthewjones372.pelican") version "1.0.0-RC3"
}

val pelicanVersion = "1.0.0-RC3"

// The generator runs off this module's classpath rather than shipping its own, and nothing the
// service runs needs it: so it is on the generating task's classpath and nowhere else.
val codegen: Configuration = configurations.create("codegen")

dependencies {
    api("io.github.matthewjones372:pelican-core:$pelicanVersion")
    api("io.github.matthewjones372:pelican-jackson:$pelicanVersion")
    codegen("io.github.matthewjones372:pelican-codegen:$pelicanVersion")
}

kotlin.sourceSets.named("main") { kotlin.srcDir("src/main/generated") }

// Pelican's check loads `registrySpec()` in Gradle's own JVM, not this module's toolchain, so the module is
// compiled for the 21 Gradle runs on. Nothing here needs lark, the one dependency that asks for 25.
kotlin { jvmToolchain(21) }

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
