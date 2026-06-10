package dev.arxael.merge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The durability journal: pending() must return exactly the PRs that were submitted but never finished —
 * that's what gets replayed after a crash (recovering lost queue entries + re-gating unverified optimistic
 * lands). A miss here either loses a PR or replays a finished one.
 */
class PrJournalTest {
    private val tmp: Path = Files.createTempDirectory("arxael-journal-test")

    @AfterTest
    fun cleanup() = Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) }.let {} }

    @Test
    fun `pending is empty with no journal`() {
        assertEquals(emptyList(), PrJournal(tmp.resolve("none")).pending())
    }

    @Test
    fun `pending returns only submitted-but-not-done, in submit order, preserving module and agent`() {
        val j = PrJournal(tmp.resolve("j"))
        j.submit(PullRequest("a", ":mod1", agentId = "A"))
        j.submit(PullRequest("b", ":mod2", agentId = "B"))
        j.submit(PullRequest("c", null, agentId = null))
        j.done("b") // b finished
        val pending = j.pending()
        assertEquals(listOf("a", "c"), pending.map { it.branch })
        assertEquals(":mod1", pending[0].module)
        assertEquals("A", pending[0].agentId)
        assertEquals(null, pending[1].module)
    }

    @Test
    fun `a fresh journal reading a prior run's file recovers its unfinished PRs (crash survival)`() {
        val path = tmp.resolve("shared")
        // "run 1" submits three, finishes one, then 'crashes' (no clean done for a,c)
        PrJournal(path).apply {
            submit(PullRequest("a", ":mod1"))
            submit(PullRequest("b", ":mod2"))
            submit(PullRequest("c", ":mod3"))
            done("b")
        }
        // "run 2" (new process) reads the same journal file and recovers a + c
        assertEquals(listOf("a", "c"), PrJournal(path).pending().map { it.branch })
    }

    @Test
    fun `self-compacts under churn without losing pending PRs or growing unbounded`() {
        val path = tmp.resolve("churn")
        val j = PrJournal(path)
        j.submit(PullRequest("keep1", ":mod1"))
        repeat(1200) { i -> j.submit(PullRequest("t$i", ":mod2")); j.done("t$i") } // crosses the compaction threshold
        j.submit(PullRequest("keep2", ":mod3"))
        // pending is exactly the two we never finished, regardless of the churn
        assertEquals(listOf("keep1", "keep2"), j.pending().map { it.branch })
        // and the on-disk journal stays BOUNDED (~one compaction window) instead of growing with total churn
        // (~2400 lines without compaction)
        val lines = Files.readAllLines(path).size
        assertTrue(lines < 1100, "journal should stay bounded by compaction (was $lines lines)")
    }

    @Test
    fun `re-submitting then finishing a recovered PR clears it from pending`() {
        val path = tmp.resolve("k")
        PrJournal(path).submit(PullRequest("a", ":mod1"))
        assertEquals(listOf("a"), PrJournal(path).pending().map { it.branch })
        PrJournal(path).done("a") // recovered run finishes it
        assertEquals(emptyList(), PrJournal(path).pending())
    }
}
