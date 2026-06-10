package dev.arxael.executor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The runtime-resizable permit gate the governor drives. Growth must add acquirable permits; shrink must
 * remove them gracefully (drive available negative when in use, never throw, never kill in-flight work).
 */
class AdjustableSemaphoreTest {

    @Test
    fun `grow releases acquirable permits`() {
        val s = AdjustableSemaphore(2)
        assertTrue(s.tryAcquire(2))
        assertFalse(s.tryAcquire()) // exhausted at 2
        s.adjustTo(4)               // grow to 4
        assertEquals(4, s.configuredPermits())
        assertTrue(s.tryAcquire(2)) // the two new permits are acquirable
        assertFalse(s.tryAcquire())
    }

    @Test
    fun `shrink removes future permits without affecting in-flight holders`() {
        val s = AdjustableSemaphore(4)
        assertTrue(s.tryAcquire(3))             // 3 in flight, 1 free
        s.adjustTo(2)                            // shrink ceiling to 2 (below the 3 in flight)
        assertEquals(2, s.configuredPermits())
        assertFalse(s.tryAcquire())              // nothing free; available was driven negative
        s.release(3)                             // the 3 in-flight finish
        // net configured is 2, so exactly 2 are acquirable again
        assertTrue(s.tryAcquire(2))
        assertFalse(s.tryAcquire())
    }
}
