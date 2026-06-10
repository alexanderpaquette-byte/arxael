package dev.arxael.adapter

import dev.arxael.config.BoxConfig
import dev.arxael.protocol.InvokeSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The generic command adapter — proves the SPI runs any toolchain. Exit 0 = success, non-zero = ordinary
 * build failure, output captured, runs in the worktree, a missing binary is an infra fault (throws).
 */
class ExecAdapterTest {
    private val tmp: Path = Files.createTempDirectory("arxael-exec-adapter-test")
    private val adapter = ExecAdapter()

    @AfterTest
    fun cleanup() = Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) }.let {} }

    private fun cfg() = BoxConfig(
        cores = 1, warmServers = 1, agentsPerCore = 1.0, coreBound = 1, memBound = 1, maxConcurrent = 1,
        bindingConstraint = "override", usableRamMb = 8000, ramHeadroomMb = 1000, perBuildFootprintMb = 1536,
        heapPerServerMb = 256, buildWorkers = 1, buildCache = false, perWorktreeHome = false,
        gradleHome = Path.of("/x"), stateDir = tmp, port = 0, watchdogIntervalMs = 2000,
        acquireTimeoutMs = 2000, reservedHigh = 0, daemonIdleSec = 120,
    )

    private fun run(vararg cmd: String): RunResult {
        val s = adapter.open(tmp, tmp.resolve("out"), cfg())
        val out = StringBuilder()
        return s.run(InvokeSpec(adapter = "exec", worktree = tmp.toString(), tasks = cmd.toList())) { out.append(it) }
            .also { s.close() }
    }

    @Test
    fun `exit zero is success, non-zero is an ordinary failure`() {
        assertTrue(adapter.name == "exec")
        assertTrue(run("true").success)
        assertFalse(run("false").success)
    }

    @Test
    fun `captures command output and runs in the worktree`() {
        Files.writeString(tmp.resolve("marker.txt"), "hi")
        val out = StringBuilder()
        val s = adapter.open(tmp, tmp.resolve("o"), cfg())
        val r = s.run(InvokeSpec(adapter = "exec", worktree = tmp.toString(), tasks = listOf("ls"))) { out.append(it) }
        assertTrue(r.success)
        assertTrue(out.contains("marker.txt"), "ran in the worktree and captured its output")
    }

    @Test
    fun `a hung process is killed at the timeout, not after it finishes`() {
        // `sleep 30` holds stdout open without writing -> the old inline drain blocked for the full 30s and
        // the timeout never fired. With off-thread draining the timeout fires and force-kills it.
        val s = adapter.open(tmp, tmp.resolve("hang"), cfg().copy(acquireTimeoutCapMs = 800))
        val start = System.currentTimeMillis()
        val r = s.run(InvokeSpec(adapter = "exec", worktree = tmp.toString(), tasks = listOf("sleep", "30"))) {}
        val elapsed = System.currentTimeMillis() - start
        assertFalse(r.success)
        assertTrue(r.message?.contains("timed out") == true, "message=${r.message}")
        assertTrue(elapsed < 10_000, "must return near the 800ms timeout, not after the 30s sleep; elapsed=${elapsed}ms")
    }

    @Test
    fun `an empty command is a failure, a missing binary is an infra fault`() {
        assertFalse(adapter.open(tmp, tmp.resolve("e"), cfg()).run(InvokeSpec(adapter = "exec", worktree = tmp.toString())) {}.success)
        var threw = false
        try { run("definitely-not-a-real-binary-xyz") } catch (e: Exception) { threw = true }
        assertTrue(threw, "a missing binary fails closed (throws -> ERROR), not a silent success")
    }
}
