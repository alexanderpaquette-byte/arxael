// Root build. Kotlin/JVM is the chosen substrate language because the first adapter
// (gradle) must hold a *warm* Tooling-API ProjectConnection in-process — a JVM library.
// See docs/ARCHITECTURE.md for the full rationale.
plugins {
    kotlin("jvm") version "1.9.24" apply false
    kotlin("plugin.serialization") version "1.9.24" apply false
}

// Single source of truth for the artifact version: the VERSION file (release tooling sets it in the public
// tree). So the jar name, its manifest, the dist dir, and `arxael --version` all agree on the release version
// instead of a hardcoded constant. Falls back for a bare checkout with no VERSION file.
val arxaelVersion = rootProject.file("VERSION").takeIf { it.exists() }
    ?.readText()?.trim()?.ifEmpty { null } ?: "0.0.0-dev"

allprojects {
    group = "dev.arxael"
    version = arxaelVersion
}
