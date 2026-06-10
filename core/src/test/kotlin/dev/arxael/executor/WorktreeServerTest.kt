package dev.arxael.executor

import dev.arxael.adapter.AdapterSession
import dev.arxael.adapter.RunResult
import dev.arxael.protocol.InvokeSpec
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The invariant in the small: one warm server serializes ONE invocation at a time, exposes a `busy`
 * flag (so the watchdog can skip it), delegates `healthy`, and closes its session exactly once.
 */
class WorktreeServerTest {
    private class FakeSession(
        var healthyResult: Boolean = true,
        val onRun: () -> Unit = {},
    ) : AdapterSession {
        var closed = 0; var runs = 0
        override fun run(spec: InvokeSpec, sink: (String) -> Unit): RunResult { runs++; onRun(); sink("ok"); return RunResult(true) }
        override fun healthy() = healthyResult
        override fun close() { closed++ }
    }

    private fun spec() = InvokeSpec(adapter = "noop", worktree = "/wt")

    @Test
    fun `run serializes, streams output, updates lastUsed, and reports busy only while running`() {
        val s = WorktreeServer("ws-1", "k", FakeSession())
        assertFalse(s.busy)
        val before = s.lastUsed()
        val out = StringBuilder()
        val r = s.run(spec()) { out.append(it) }
        assertTrue(r.success); assertEquals("ok", out.toString())
        assertFalse(s.busy, "not busy after run returns")
        assertTrue(s.lastUsed() >= before)
    }

    @Test
    fun `busy is true while a run is in flight (so the watchdog skips it)`() {
        val gate = Object()
        val fake = FakeSession(onRun = { synchronized(gate) { /* hold inside run */ } })
        val s = WorktreeServer("ws-2", "k", fake)
        var t: Thread
        synchronized(gate) {
            t = thread { s.run(spec()) {} }
            Thread.sleep(150) // let the run start and block inside onRun
            assertTrue(s.busy, "busy while the build is running")
        }
        t.join()
        assertFalse(s.busy)
    }

    @Test
    fun `a run on a closed (evicted) server throws ServerEvicted and never touches the session`() {
        val fake = FakeSession()
        val s = WorktreeServer("ws-4", "k", fake)
        s.close()
        var threw = false
        try { s.run(spec()) {} } catch (e: ServerEvicted) { threw = true }
        assertTrue(threw, "run after eviction must throw ServerEvicted, not run the torn-down session")
        assertEquals(0, fake.runs, "the closed session is never run")
        assertEquals(1, fake.closed)
    }

    @Test
    fun `healthy delegates to the session, and close tears it down`() {
        val fake = FakeSession(healthyResult = false)
        val s = WorktreeServer("ws-3", "k", fake)
        assertFalse(s.healthy())
        fake.healthyResult = true
        assertTrue(s.healthy())
        s.close()
        assertEquals(1, fake.closed)
    }
}
