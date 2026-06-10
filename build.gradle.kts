// Root build. Kotlin/JVM is the chosen substrate language because the first adapter
// (gradle) must hold a *warm* Tooling-API ProjectConnection in-process — a JVM library.
// See docs/ARCHITECTURE.md for the full rationale.
plugins {
    kotlin("jvm") version "1.9.24" apply false
    kotlin("plugin.serialization") version "1.9.24" apply false
}

allprojects {
    group = "dev.arxael"
    version = "0.1.0"
}
