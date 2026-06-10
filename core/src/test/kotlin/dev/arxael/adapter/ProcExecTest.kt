package dev.arxael.adapter

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Process runner edge cases that wedge a permit or mis-report if mishandled: a child that leaves a GRANDCHILD
 * holding the stdout pipe open (destroyForcibly only kills the direct child), and signal-killed exits. These
 * are Linux/sh dependent — skipped cleanly if `sh` isn't present.
 */
class ProcExecTest {
    private val tmp: Path = Files.createTempDirectory("arxael-procexec-test")
    private val hasSh = Files.exists(Path.of("/bin/sh"))

    @AfterTest
    fun cleanup() = Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) }.let {} }

    private fun drainThreadsAlive() =
        Thread.getAllStackTraces().keys.count { it.name == "procexec-drain" && it.isAlive }

    @Test
    fun `a detached grandchild on the pipe does not block the normal-exit path (prompt, with output)`() {
        if (!hasSh) return
        // The shell prints "hi" and exits 0 immediately, backgrounding a `sleep 30` that INHERITS stdout and
        // then reparents to init — it's no longer reachable to kill. The run must NOT block on its 30s lifetime;
        // it returns promptly with the captured output. (That drain is a daemon thread holding no permit.)
        val sb = StringBuilder()
        val start = System.nanoTime()
        val r = ProcExec.run(tmp, listOf("/bin/sh", "-c", "echo hi; sleep 30 &"), timeoutMs = 20_000) { sb.append(it) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(r.success, "the direct child exited 0 -> success")
        assertTrue(sb.toString().contains("hi"), "output captured before the detached grandchild: '${sb}'")
        assertTrue(elapsedMs < 15_000, "must not block on the grandchild's 30s lifetime (took ${elapsedMs}ms)")
    }

    @Test
    fun `timeout kills the whole tree (grandchild included) and the drain does not leak`() {
        if (!hasSh) return
        val before = drainThreadsAlive()
        val start = System.nanoTime()
        // direct child (sh) waits on sleep 30; a backgrounded `sleep 30 &` is still a CHILD of sh (descendant of
        // the process) at kill time, so destroyTree reaps it -> the pipe closes -> the drain unblocks (no leak).
        val r = ProcExec.run(tmp, listOf("/bin/sh", "-c", "sleep 30 & sleep 30"), timeoutMs = 800) {}
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertFalse(r.success)
        assertTrue(r.message?.contains("timed out") == true, "message: ${r.message}")
        assertTrue(elapsedMs < 12_000, "timeout path must return promptly (took ${elapsedMs}ms)")
        Thread.sleep(500)
        assertTrue(drainThreadsAlive() <= before, "the tree-kill must let this call's drain terminate (no new leak)")
    }

    @Test
    fun `a signal-killed child is reported distinctly from an ordinary test failure`() {
        if (!hasSh) return
        val killed = ProcExec.run(tmp, listOf("/bin/sh", "-c", "kill -9 \$\$"), timeoutMs = 5_000) {}
        assertFalse(killed.success)
        assertTrue(killed.message?.contains("signal 9") == true, "expected signal classification, got: ${killed.message}")

        val red = ProcExec.run(tmp, listOf("/bin/sh", "-c", "exit 3"), timeoutMs = 5_000) {}
        assertFalse(red.success)
        assertEquals("exit 3", red.message, "an ordinary non-zero exit is a plain build failure")

        val green = ProcExec.run(tmp, listOf("/bin/sh", "-c", "true"), timeoutMs = 5_000) {}
        assertTrue(green.success)
        assertEquals(null, green.message)
    }
}
