package dev.arxael.eventlog

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The audit log must not grow without bound — a long-running daemon that fills the disk silently breaks the
 * journal and every build write. With size rotation, the live file stays under the cap and at most one prior
 * generation is retained (total bounded at ~2× cap), no matter how many events are emitted.
 */
class EventLogRotationTest {
    private val tmp: Path = Files.createTempDirectory("arxael-eventlog-rot")

    @AfterTest
    fun cleanup() = Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) }.let {} }

    @Test
    fun `the log rotates at the cap and total on-disk stays bounded`() {
        val path = tmp.resolve("events.jsonl")
        val cap = 4_000L // tiny cap so the test rotates quickly
        val log = EventLog(path, maxBytes = cap)
        repeat(2_000) { i -> log.emit("invoke_done", mapOf("i" to i, "key" to "worktree-$i", "status" to "SUCCESS")) }
        log.close()

        val live = Files.size(path)
        val rotated = path.resolveSibling("${path.fileName}.1")
        assertTrue(Files.exists(rotated), "a prior generation should have been rotated out")
        // live file is bounded by the cap (+ at most one over-cap line), and total is at most ~2 generations
        assertTrue(live < cap + 4_096, "live log must stay near the cap, was $live")
        val total = live + Files.size(rotated)
        assertTrue(total < 2 * cap + 8_192, "total on-disk (live + .1) must stay bounded, was $total")
    }

    @Test
    fun `recentErrors survives rotation`() {
        val path = tmp.resolve("events.jsonl")
        val log = EventLog(path, maxBytes = 2_000L)
        repeat(500) { i -> log.emit("invoke_done", mapOf("i" to i)) } // forces several rotations
        log.emit("merge_revert_failed", mapOf("branch" to "x"))       // a fault AFTER rotations
        val recent = log.recentErrors()
        log.close()
        assertTrue(recent.any { it.contains("merge_revert_failed") }, "the recent-errors ring is in-memory and unaffected by rotation")
    }
}
