package dev.arxael.autosize

import dev.arxael.adapter.AdapterRegistry
import dev.arxael.config.BoxConfig
import dev.arxael.eventlog.EventLog
import dev.arxael.executor.WarmExecutor
import dev.arxael.protocol.InvokeSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The governor's /proc parsing (pure) and its control step (tick) driven by injected fake sensors — so the
 * adapt-to-the-real-box behaviour is unit-tested without depending on the host's actual /proc values.
 */
class AdaptiveGovernorTest {
    private val tmp: Path = Files.createTempDirectory("arxael-gov-test")
    private val events = EventLog(tmp.resolve("events.jsonl"))

    @AfterTest
    fun cleanup() {
        events.close()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    private fun cfg() = BoxConfig(
        cores = 8, warmServers = 8, agentsPerCore = 1.0, coreBound = 8, memBound = 8, maxConcurrent = 8,
        bindingConstraint = "override", usableRamMb = 10_000, ramHeadroomMb = 2_000, perBuildFootprintMb = 1500,
        heapPerServerMb = 256, buildWorkers = 1, buildCache = false, perWorktreeHome = false,
        gradleHome = Path.of("/opt/gradle/none"), stateDir = tmp, port = 0, watchdogIntervalMs = 2000,
        acquireTimeoutMs = 2000, reservedHigh = 0, daemonIdleSec = 120,
        concurrencyFloor = 1, concurrencyCeiling = 8,
    )

    // ---- pure parsers ----
    @Test
    fun `parseMeminfo extracts total + available in MB`() {
        val m = AdaptiveGovernor.parseMeminfo(listOf("MemTotal:    8000000 kB", "MemFree: 1 kB", "MemAvailable:  4000000 kB"))
        assertEquals(AdaptiveGovernor.Mem(totalMb = 7812, availMb = 3906), m)
        assertNull(AdaptiveGovernor.parseMeminfo(listOf("MemTotal: 8 kB")), "missing MemAvailable -> null")
    }

    @Test
    fun `parseLoad1 reads the 1-minute load, tolerates junk`() {
        assertEquals(2.5, AdaptiveGovernor.parseLoad1("2.50 1.20 0.80 1/234 5678"))
        assertNull(AdaptiveGovernor.parseLoad1("not-a-number x y"))
    }

    @Test
    fun `parseStatTotalIoWait sums jiffies and picks the iowait field`() {
        val r = AdaptiveGovernor.parseStatTotalIoWait(listOf("cpu  100 0 50 800 40 0 10", "cpu0 ..."))
        assertEquals((100L + 0 + 50 + 800 + 40 + 0 + 10) to 40L, r)
        assertNull(AdaptiveGovernor.parseStatTotalIoWait(listOf("intr 1 2 3")), "no cpu line -> null")
        assertNull(AdaptiveGovernor.parseStatTotalIoWait(listOf("cpu 1 2 3 4")), "fewer than 5 fields (no iowait) -> null")
    }

    // ---- tick (control step) with injected sensors ----
    @Test
    fun `tick shrinks the live bound when memory is under the headroom floor`() {
        val ex = WarmExecutor(cfg(), AdapterRegistry.default(), events)
        try {
            val gov = AdaptiveGovernor(ex, cfg(), events)
            gov.memSource = { AdaptiveGovernor.Mem(totalMb = 10_000, availMb = 1_000) } // avail < headroom 2000
            gov.load1Source = { 0.0 }; gov.ioWaitSource = { null }
            assertEquals(8, ex.concurrencyTarget())
            gov.tick()
            assertEquals(6, ex.concurrencyTarget(), "pressure -> multiplicative shrink (8 - max(1,8/4))")
        } finally { ex.shutdown() }
    }

    @Test
    fun `tick holds the bound when memory is comfortable and nothing is waiting`() {
        val ex = WarmExecutor(cfg(), AdapterRegistry.default(), events)
        try {
            val gov = AdaptiveGovernor(ex, cfg(), events)
            gov.memSource = { AdaptiveGovernor.Mem(totalMb = 10_000, availMb = 8_000) } // ample headroom
            gov.load1Source = { 0.0 }; gov.ioWaitSource = { null }
            gov.tick()
            assertEquals(8, ex.concurrencyTarget(), "ample memory + no demand -> unchanged")
        } finally { ex.shutdown() }
    }

    @Test
    fun `tick learns the per-build footprint while a build is in flight`() {
        val ex = WarmExecutor(cfg(), AdapterRegistry.default(), events)
        try {
            val gov = AdaptiveGovernor(ex, cfg(), events)
            gov.load1Source = { 0.0 }; gov.ioWaitSource = { null }
            // 1) idle tick establishes the baseline (used when nothing is in flight)
            gov.memSource = { AdaptiveGovernor.Mem(totalMb = 100_000, availMb = 96_000) } // used ~4000
            gov.tick()
            val seed = gov.footprintMb
            // 2) a build is in flight and memory used jumps -> the footprint estimate moves off its seed
            val holder = thread { ex.submit(InvokeSpec(adapter = "noop", worktree = "/wt", args = listOf("sleepMs=1500"))) }
            Thread.sleep(250)
            gov.memSource = { AdaptiveGovernor.Mem(totalMb = 100_000, availMb = 90_000) } // used ~10000 (+6000 over baseline)
            gov.tick()
            assertTrue(gov.footprintMb != seed, "footprint should update from the in-flight observation (was $seed)")
            holder.join()
        } finally { ex.shutdown() }
    }

    @Test
    fun `tick is a no-op if the memory sensor is unavailable`() {
        val ex = WarmExecutor(cfg(), AdapterRegistry.default(), events)
        try {
            val gov = AdaptiveGovernor(ex, cfg(), events)
            gov.memSource = { null }
            gov.tick()
            assertEquals(8, ex.concurrencyTarget())
            assertTrue(gov.snapshot()["adaptive"] == true)
        } finally { ex.shutdown() }
    }
}
