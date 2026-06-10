package dev.arxael.util

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Crash-safe file replacement: write to a temp sibling, then atomically rename it over the target. A reader
 * (or a restart) therefore sees either the COMPLETE old file or the COMPLETE new file — never a truncated or
 * half-written one. This matters for every file the daemon's recovery/learning reads back after a crash:
 * truncate-in-place (`Files.writeString(path, …)`) can lose the whole file if the process dies mid-write,
 * which for the PR journal would strand an unverified change on main. Always replace; never truncate in place.
 */
object AtomicWrite {
    fun writeString(path: Path, content: String) {
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, content)
        try {
            Files.move(tmp, path, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // rare filesystem without atomic rename -> best-effort replace (still better than truncate-in-place)
            Files.move(tmp, path, REPLACE_EXISTING)
        }
    }
}
