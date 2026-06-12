package dev.arxael

/**
 * The running engine's build version, baked into a classpath resource (`/arxael-version`) at build time from
 * the single-source root `VERSION` file (see `core/build.gradle.kts` → `generateVersionResource`).
 *
 * Reported on `/health` (`version`) and `/metrics` (`arxael_build_info{version=...}`) so an operator — or the
 * `arxael` CLI — can tell exactly which engine is live, detect a stale warm daemon after an upgrade, and tag
 * telemetry with the version that produced it. Falls back to `0.0.0-dev` for a bare classpath (no resource).
 */
object BuildInfo {
    val version: String by lazy {
        BuildInfo::class.java.getResourceAsStream("/arxael-version")
            ?.bufferedReader()?.use { it.readText().trim() }
            ?.ifEmpty { null }
            ?: "0.0.0-dev"
    }
}
