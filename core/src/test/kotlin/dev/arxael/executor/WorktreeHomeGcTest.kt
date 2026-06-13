package dev.arxael.executor

import dev.arxael.adapter.AdapterRegistry
import dev.arxael.config.BoxConfig
import dev.arxael.eventlog.EventLog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Orphaned per-worktree gradle homes must be reclaimed independent of warm-pool cap
 * pressure. [WarmExecutor.gcOrphanedWorktrees] deletes output-bases with no live server that are older than
 * the create-window TTL, and the [Watchdog] now sweeps it periodically (the on-demand GC only ran when the
 * pool was OVER cap, so under-cap churn accumulated homes forever). These pin both the GC logic and the
 * watchdog wiring.
 */
class WorktreeHomeGcTest {
    private val tmp: Path = Files.createTempDirectory("arxael-wtgc-test")
    private val events = EventLog(tmp.resolve("events.jsonl"))
    private var executor: WarmExecutor? = null

    private fun cfg() = BoxConfig(
        cores = 2, warmServers = 8, agentsPerCore = 1.0, coreBound = 2, memBound = 2, maxConcurrent = 2,
        bindingConstraint = "override", usableRamMb = 8000, ramHeadroomMb = 1000, perBuildFootprintMb = 1536,
        heapPerServerMb = 256, buildWorkers = 1, buildCache = false, perWorktreeHome = false,
        gradleHome = Path.of("/opt/gradle/none"), stateDir = tmp, port = 0, watchdogIntervalMs = 2000,
        acquireTimeoutMs = 2000, reservedHigh = 0, daemonIdleSec = 120,
    )

    // Create an orphaned output-base under <stateDir>/worktrees with the given age (ms ago) on its mtime.
    private fun orphan(name: String, ageMs: Long): Path {
        val dir = tmp.resolve("worktrees").resolve(name)
        Files.createDirectories(dir.resolve("gradle-user-home"))
        Files.writeString(dir.resolve("gradle-user-home").resolve("caches.txt"), "x".repeat(64))
        Files.setLastModifiedTime(dir, FileTime.fromMillis(System.currentTimeMillis() - ageMs))
        return dir
    }

    @AfterTest
    fun cleanup() {
        executor?.shutdown(); events.close()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    @Test
    fun `gc reclaims an orphan older than the TTL but spares a freshly-touched one`() {
        val ex = WarmExecutor(cfg(), AdapterRegistry.default(), events).also { executor = it }
        val stale = orphan("stalehash", 24L * 60 * 60 * 1000) // older than the 10-min TTL
        val fresh = orphan("freshhash", 1_000)                                    // touched 1s ago -> spared

        val reclaimed = ex.gcOrphanedWorktrees()

        assertEquals(1, reclaimed, "exactly the stale orphan is reclaimed")
        assertFalse(Files.exists(stale), "the stale orphan home is deleted")
        assertTrue(Files.exists(fresh), "the create-window TTL spares the freshly-touched orphan")
    }

    @Test
    fun `the watchdog tick sweeps orphaned homes`() {
        val ex = WarmExecutor(cfg(), AdapterRegistry.default(), events).also { executor = it }
        val stale = orphan("wtdog", 24L * 60 * 60 * 1000)
        assertTrue(Files.exists(stale))

        // lastWorktreeGcMs starts at 0, so the very first tick runs the sweep (no warm pool over cap needed).
        Watchdog(ex, events, cfg()).tick()

        assertFalse(Files.exists(stale), "the watchdog reclaimed the orphaned home without any cap pressure")
    }
}
