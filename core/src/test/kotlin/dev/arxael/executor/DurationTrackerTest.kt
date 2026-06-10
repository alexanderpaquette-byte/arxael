package dev.arxael.executor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the adaptive overload timeout: it must report 0 until builds complete (so the executor uses the
 * static configured floor), then track observed durations via EWMA as builds lengthen with the project.
 */
class DurationTrackerTest {

    @Test
    fun `reports zero until a build completes`() {
        assertEquals(0, DurationTracker().typicalMs())
    }

    @Test
    fun `ignores non-positive samples`() {
        val t = DurationTracker()
        t.record(0); t.record(-5)
        assertEquals(0, t.typicalMs())
    }

    @Test
    fun `first sample seeds the estimate, then EWMA tracks toward newer durations`() {
        val t = DurationTracker(alpha = 0.5)
        t.record(1000)
        assertEquals(1000, t.typicalMs())     // seed
        t.record(3000)
        assertEquals(2000, t.typicalMs())     // 0.5*3000 + 0.5*1000
    }

    @Test
    fun `tracks upward as builds get longer (a growing project)`() {
        val t = DurationTracker(alpha = 0.5)
        t.record(1000)
        repeat(6) { t.record(10_000) }        // builds get much slower
        assertTrue(t.typicalMs() > 5000, "estimate should climb toward the longer durations")
    }
}
