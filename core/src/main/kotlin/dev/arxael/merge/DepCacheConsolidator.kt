package dev.arxael.merge

import dev.arxael.eventlog.EventLog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread

/**
 * Makes per-worktree Gradle homes safe by default: each per-worktree build downloads new dependencies into
 * its OWN home (no shared-home lock), and this consolidator periodically copies those newly-downloaded
 * artifacts into ONE shared cache that every build then reads read-only (GRADLE_RO_DEP_CACHE). So the shared
 * cache self-fills from real usage and re-downloads converge to ~zero — without the cross-process lock that
 * a shared *writable* home would impose.
 *
 * Safe by construction: Gradle's `caches/modules-2` is a content-addressed artifact store, and seeding a
 * read-only dep cache by copying a populated `modules-2` is a supported Gradle pattern. We only ever copy
 * files that are ABSENT in the shared cache (additive, idempotent) — never overwrite — so a concurrent build
 * reading the shared cache can't observe a torn file.
 */
class DepCacheConsolidator(
    private val worktreesRoot: Path,   // <stateDir>/worktrees — per-worktree homes live under here
    private val sharedCache: Path,     // <stateDir>/shared-deps/caches — the RO cache builds read
    private val events: EventLog,
    private val intervalMs: Long = 10_000,
) {
    @Volatile private var running = false
    private var loop: Thread? = null

    fun start() {
        if (running) return
        running = true
        loop = thread(name = "dep-cache-consolidator", isDaemon = true) {
            while (running) {
                try { val n = consolidate(); if (n > 0) events.emit("dep_cache_consolidated", mapOf("files" to n)) }
                catch (e: Exception) { events.emit("dep_cache_consolidate_error", mapOf("error" to (e.message ?: e.toString()))) }
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun stop() { running = false; loop?.join(2000) }

    /** Copy every per-worktree home's modules-2 artifacts into the shared cache (new files only). Returns count. */
    fun consolidate(): Int {
        if (!Files.isDirectory(worktreesRoot)) return 0
        var copied = 0
        Files.list(worktreesRoot).use { wts ->
            wts.forEach { wt ->
                val src = wt.resolve("gradle-user-home/caches/modules-2")
                if (Files.isDirectory(src)) copied += copyNew(src, sharedCache.resolve("modules-2"))
            }
        }
        return copied
    }

    companion object {
        /** Copy regular files present under [srcDir] but ABSENT under [dstDir] (same relative path). Never
         *  overwrites (so a concurrent reader can't see a partial file). Returns the number copied. */
        fun copyNew(srcDir: Path, dstDir: Path): Int {
            if (!Files.isDirectory(srcDir)) return 0
            var copied = 0
            // srcDir is a LIVE per-worktree cache an active build concurrently writes/GCs, so walk entries can
            // vanish mid-iteration. Drive the iterator defensively: a throw from next() (UncheckedIOException as
            // the tree mutates) ends THIS source's walk, and a per-file error skips just that file — never
            // aborting the whole consolidation pass.
            Files.walk(srcDir).use { stream ->
                val it = stream.iterator()
                while (true) {
                    val src = try {
                        if (!it.hasNext()) break
                        it.next()
                    } catch (_: Exception) { break } // tree mutated under us -> stop this source, others continue
                    try {
                        if (!Files.isRegularFile(src)) continue
                        val dst = dstDir.resolve(srcDir.relativize(src))
                        if (Files.exists(dst)) continue
                        Files.createDirectories(dst.parent)
                        // copy to a temp sibling then atomic-move, so a reader never sees a half-written file
                        val tmp = dst.resolveSibling(dst.fileName.toString() + ".part")
                        Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
                        try {
                            Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE)
                        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                            // rare FS without atomic rename: fall back so the cache still fills (was silently
                            // failing every copy -> cache never filled). Still temp-then-move, just not atomic.
                            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING)
                        }
                        copied++
                    } catch (_: Exception) { /* vanished/permission/torn: skip this file, keep consolidating */ }
                }
            }
            return copied
        }
    }
}
