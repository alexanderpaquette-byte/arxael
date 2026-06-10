package dev.arxael.merge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepCacheConsolidatorTest {
    private val tmp: Path = Files.createTempDirectory("depconsol")

    @AfterTest fun cleanup() { tmp.toFile().deleteRecursively() }

    private fun write(p: Path, content: String) { p.parent.createDirectories(); p.writeText(content) }

    @Test
    fun `copies files absent in the destination preserving relative layout`() {
        val src = tmp.resolve("src"); val dst = tmp.resolve("dst")
        write(src.resolve("files-2.1/g/a/1.0/sha/a.jar"), "AAA")
        write(src.resolve("files-2.1/g/b/2.0/sha/b.jar"), "BBB")

        val copied = DepCacheConsolidator.copyNew(src, dst)

        assertEquals(2, copied)
        assertEquals("AAA", dst.resolve("files-2.1/g/a/1.0/sha/a.jar").readText())
        assertEquals("BBB", dst.resolve("files-2.1/g/b/2.0/sha/b.jar").readText())
    }

    @Test
    fun `never overwrites existing destination files`() {
        val src = tmp.resolve("src"); val dst = tmp.resolve("dst")
        write(src.resolve("x/a.jar"), "NEW")
        write(dst.resolve("x/a.jar"), "ORIGINAL") // already present -> must be left untouched

        val copied = DepCacheConsolidator.copyNew(src, dst)

        assertEquals(0, copied)
        assertEquals("ORIGINAL", dst.resolve("x/a.jar").readText())
    }

    @Test
    fun `copies only the new files when some already exist`() {
        val src = tmp.resolve("src"); val dst = tmp.resolve("dst")
        write(src.resolve("x/a.jar"), "A")
        write(src.resolve("x/b.jar"), "B")
        write(dst.resolve("x/a.jar"), "A") // a exists, b is new

        assertEquals(1, DepCacheConsolidator.copyNew(src, dst))
        assertTrue(Files.exists(dst.resolve("x/b.jar")))
    }

    @Test
    fun `missing source directory copies nothing`() {
        assertEquals(0, DepCacheConsolidator.copyNew(tmp.resolve("does-not-exist"), tmp.resolve("dst")))
    }

    @Test
    fun `consolidate scans every per-worktree home into the shared cache`() {
        val worktrees = tmp.resolve("worktrees"); val shared = tmp.resolve("shared/caches")
        write(worktrees.resolve("wt1/gradle-user-home/caches/modules-2/files-2.1/a.jar"), "A")
        write(worktrees.resolve("wt2/gradle-user-home/caches/modules-2/files-2.1/b.jar"), "B")

        val consolidator = DepCacheConsolidator(worktrees, shared, dev.arxael.eventlog.EventLog(tmp.resolve("ev.jsonl")))
        val copied = consolidator.consolidate()

        assertEquals(2, copied)
        assertTrue(Files.exists(shared.resolve("modules-2/files-2.1/a.jar")))
        assertTrue(Files.exists(shared.resolve("modules-2/files-2.1/b.jar")))
        // idempotent: a second pass copies nothing new
        assertEquals(0, consolidator.consolidate())
    }

    @Test
    fun `consolidate with no worktrees root copies nothing`() {
        val consolidator = DepCacheConsolidator(tmp.resolve("nope"), tmp.resolve("shared"), dev.arxael.eventlog.EventLog(tmp.resolve("ev.jsonl")))
        assertEquals(0, consolidator.consolidate())
    }
}
