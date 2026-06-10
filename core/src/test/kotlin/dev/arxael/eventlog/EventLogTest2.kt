package dev.arxael.eventlog

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The recent-errors ring that feeds /health's at-a-glance "anything wrong lately?" signal. It must capture
 * fault events (and only fault events), newest-last, bounded — so a flood of errors can't grow it unbounded
 * and ordinary events don't drown the signal.
 */
class EventLogRecentErrorsTest {
    private val tmp: Path = Files.createTempDirectory("arxael-eventlog-test")
    private val log = EventLog(tmp.resolve("events.jsonl"))

    @AfterTest
    fun cleanup() {
        log.close()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    @Test
    fun `captures only fault events, newest last`() {
        log.emit("invoke_start", mapOf("k" to "v"))          // not a fault
        log.emit("merge_gate_error", mapOf("branch" to "b1"))
        log.emit("merge_land_optimistic")                     // not a fault
        log.emit("invoke_overloaded", mapOf("queueMs" to 99))
        log.emit("merge_revert_failed", mapOf("commit" to "abc"))
        val errs = log.recentErrors()
        assertEquals(3, errs.size)
        assertTrue(errs[0].contains("merge_gate_error"))
        assertTrue(errs[1].contains("invoke_overloaded"))
        assertTrue(errs[2].contains("merge_revert_failed"), "newest fault is last")
    }

    @Test
    fun `the ring is bounded (a fault flood cannot grow it unbounded)`() {
        repeat(50) { log.emit("governor_error", mapOf("n" to it)) }
        val errs = log.recentErrors()
        assertEquals(20, errs.size, "capped at MAX_RECENT_ERRORS")
        assertTrue(errs.last().contains("\"n\":49"), "keeps the most recent")
        assertTrue(errs.none { it.contains("\"n\":29") }, "drops the oldest beyond the cap")
    }
}
