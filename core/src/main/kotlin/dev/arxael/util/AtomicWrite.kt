package dev.arxael.util

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

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
        try {
            // fsync the temp's bytes BEFORE the rename publishes it. Without the force(), a host crash can let
            // the rename be ordered ahead of the data, leaving a correctly-named but empty/garbage file — the
            // exact truncation this guards against. (Process-only SIGKILL is safe either way via the page cache.)
            FileChannel.open(tmp, CREATE, WRITE, TRUNCATE_EXISTING).use { ch ->
                val buf = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(true)
            }
            try {
                Files.move(tmp, path, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                // rare filesystem without atomic rename -> best-effort replace (still better than truncate-in-place)
                Files.move(tmp, path, REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) } // never leave a partial temp sibling behind on failure
            throw e
        }
    }
}
