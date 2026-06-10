package dev.arxael.executor

import dev.arxael.adapter.AdapterRegistry
import dev.arxael.adapter.AdapterSession
import dev.arxael.adapter.BuildAdapter
import dev.arxael.adapter.RunResult
import dev.arxael.config.BoxConfig
import dev.arxael.eventlog.EventLog
import dev.arxael.protocol.InvokeSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recovery: an unhealthy warm server is quarantined + dropped (never reconnected in place) and the next
 * invoke gets a FRESH one. Uses an adapter whose sessions can be flipped unhealthy.
 */
class WarmExecutorRecoveryTest {
    private val tmp: Path = Files.createTempDirectory("arxael-recover-test")
    private val events = EventLog(tmp.resolve("events.jsonl"))

    // Each open() makes a new session; the test flips the latest one's health.
    private class FlakyAdapter : BuildAdapter {
        override val name = "flaky"
        val sessions = mutableListOf<Session>()
        override fun open(worktree: Path, outputBase: Path, config: BoxConfig): AdapterSession =
            Session().also { sessions.add(it) }
        class Session : AdapterSession {
            @Volatile var ok = true
            var closed = 0
            override fun run(spec: InvokeSpec, sink: (String) -> Unit) = RunResult(true)
            override fun healthy() = ok
            override fun close() { closed++ }
        }
    }

    @AfterTest
    fun cleanup() {
        events.close()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    private fun cfg() = BoxConfig(
        cores = 2, warmServers = 8, agentsPerCore = 1.0, coreBound = 2, memBound = 2, maxConcurrent = 2,
        bindingConstraint = "override", usableRamMb = 8000, ramHeadroomMb = 1000, perBuildFootprintMb = 1536,
        heapPerServerMb = 256, buildWorkers = 1, buildCache = false, perWorktreeHome = false,
        gradleHome = Path.of("/opt/gradle/none"), stateDir = tmp, port = 0, watchdogIntervalMs = 2000,
        acquireTimeoutMs = 2000, reservedHigh = 0, daemonIdleSec = 120,
    )

    @Test
    fun `an unhealthy server is quarantined and the next invoke gets a fresh one`() {
        val flaky = FlakyAdapter()
        val ex = WarmExecutor(cfg(), AdapterRegistry.of(flaky), events)
        try {
            ex.submit(InvokeSpec(adapter = "flaky", worktree = tmp.resolve("wt").toString())) // creates session #0
            assertEquals(1, flaky.sessions.size)
            assertEquals(0, ex.recoverUnhealthy(), "a healthy server is not recovered")

            flaky.sessions[0].ok = false // the warm connection goes bad
            assertEquals(1, ex.recoverUnhealthy(), "the unhealthy server is quarantined")
            assertEquals(1, flaky.sessions[0].closed, "its wedged connection is closed, not reused")

            // next invoke on the same worktree creates a FRESH session (not the dropped one)
            ex.submit(InvokeSpec(adapter = "flaky", worktree = tmp.resolve("wt").toString()))
            assertEquals(2, flaky.sessions.size, "a fresh server replaced the quarantined one")
            assertTrue(flaky.sessions[1].ok)
        } finally { ex.shutdown() }
    }
}
