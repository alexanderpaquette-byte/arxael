package dev.arxael.config

import java.nio.file.Files
import java.nio.file.Path

/**
 * Persists the governor's LEARNED per-build memory footprint (MB) across daemon restarts.
 *
 * Without this, every restart re-seeds memBound from the static 1.5 GB guess and has to re-learn the real
 * footprint from scratch — and as the agents' project grows heavier between restarts, that guess drifts
 * further from reality. Remembering the learned value makes the STARTUP bound calibrated by history, so the
 * daemon comes up already sized for the project as it actually is now, then keeps refining live.
 *
 * Best-effort: a read/write failure is non-fatal (fall back to the default); the value is one plain integer.
 */
object FootprintStore {
    private fun path(stateDir: Path): Path = stateDir.resolve("learned-footprint-mb")

    /** Last learned footprint (MB), or null if none/unreadable. */
    fun read(stateDir: Path): Long? = try {
        Files.readString(path(stateDir)).trim().toLong().takeIf { it > 0 }
    } catch (_: Exception) {
        null
    }

    fun write(stateDir: Path, mb: Long) {
        try {
            Files.createDirectories(stateDir)
            dev.arxael.util.AtomicWrite.writeString(path(stateDir), mb.toString()) // atomic, not truncate-in-place
        } catch (_: Exception) { /* best-effort */ }
    }
}
