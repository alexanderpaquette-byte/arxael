package dev.arxael.executor

import dev.arxael.adapter.AdapterRegistry
import dev.arxael.config.BoxConfig
import dev.arxael.eventlog.EventLog
import dev.arxael.protocol.InvokeSpec
import dev.arxael.protocol.InvokeStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The executor's bounded-concurrency gate and the reserved high-priority lane (added for the
 * merge-gate workflow — "high" landings must never be starved by a flood
 * of "normal" branch-tests). These paths are pure substrate correctness, so they are unit-tested
 * here against the NoopAdapter (its `sleepMs=` arg holds a permit for a deterministic window) rather
 * than left to integration coverage. A regression here either wedges the box (no fail-closed) or lets
 * a branch-test flood starve landings (reservation broken) — both silent and expensive.
 */
class WarmExecutorConcurrencyTest {
    private val tmp: Path = Files.createTempDirectory("arxael-exec-test")
    private val events = EventLog(tmp.resolve("events.jsonl"))
    private val registry = AdapterRegistry.default()

    @AfterTest
    fun cleanup() {
        events.close()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    private fun cfg(maxConcurrent: Int, reservedHigh: Int, acquireTimeoutMs: Long): BoxConfig =
        BoxConfig(
            cores = 4, warmServers = 8, agentsPerCore = 1.0,
            coreBound = maxConcurrent, memBound = maxConcurrent, maxConcurrent = maxConcurrent,
            bindingConstraint = "override", usableRamMb = 16_000, ramHeadroomMb = 2_000,
            perBuildFootprintMb = 1536, heapPerServerMb = 512, buildWorkers = 1,
            buildCache = false, perWorktreeHome = false, gradleHome = Path.of("/opt/gradle/none"),
            stateDir = tmp, port = 0, watchdogIntervalMs = 2000, acquireTimeoutMs = acquireTimeoutMs,
            reservedHigh = reservedHigh, daemonIdleSec = 120,
        )

    private fun spec(wt: String, sleepMs: Long = 0, priority: String = "normal") =
        InvokeSpec(
            adapter = "noop", worktree = wt,
            args = if (sleepMs > 0) listOf("sleepMs=$sleepMs") else emptyList(),
            priority = priority,
        )

    @Test
    fun `unknown adapter is rejected before dispatch`() {
        val ex = WarmExecutor(cfg(2, 0, 1000), registry, events)
        try {
            val out = ex.submit(InvokeSpec(adapter = "does-not-exist", worktree = tmp.toString()))
            assertEquals(InvokeStatus.REJECTED.name, out.status)
            assertEquals("-", out.server)
        } finally { ex.shutdown() }
    }

    @Test
    fun `a normal invocation runs to success on a warm server`() {
        val ex = WarmExecutor(cfg(2, 0, 1000), registry, events)
        try {
            val out = ex.submit(spec(tmp.resolve("ok").toString()))
            assertTrue(out.ok, "expected success, got ${out.status}: ${out.message}")
            assertEquals(InvokeStatus.SUCCESS.name, out.status)
        } finally { ex.shutdown() }
    }

    @Test
    fun `excess load fails closed as OVERLOADED rather than wedging`() {
        val ex = WarmExecutor(cfg(1, 0, 200), registry, events)
        try {
            // Holder occupies the single permit for well past the contender's acquire timeout.
            val holder = thread { ex.submit(spec(tmp.resolve("holder").toString(), sleepMs = 1500)) }
            Thread.sleep(250) // let the holder acquire the lone permit
            val out = ex.submit(spec(tmp.resolve("contender").toString()))
            assertEquals(InvokeStatus.OVERLOADED.name, out.status)
            assertTrue(out.queueMs >= 200, "should have waited ~the acquire timeout, got ${out.queueMs}ms")
            holder.join()
        } finally { ex.shutdown() }
    }

    @Test
    fun `reserved high lane lets a landing through while the normal lane is saturated`() {
        // maxConcurrent=2, reservedHigh=1 -> normalPermits=1, global permits=2.
        val ex = WarmExecutor(cfg(2, 1, 200), registry, events)
        try {
            // Holder takes the one normal-lane permit (and one global permit) for a long window.
            val holder = thread { ex.submit(spec(tmp.resolve("normal-holder").toString(), sleepMs = 1500)) }
            Thread.sleep(250) // let the holder claim the normal lane

            // A second NORMAL invocation finds the normal lane empty -> fails closed.
            val normalOut = ex.submit(spec(tmp.resolve("normal-2").toString()))
            assertEquals(InvokeStatus.OVERLOADED.name, normalOut.status, "normal lane should be saturated")

            // A HIGH invocation skips the normal lane and uses the reserved global permit -> succeeds.
            val highOut = ex.submit(spec(tmp.resolve("high-1").toString(), priority = "high"))
            assertEquals(InvokeStatus.SUCCESS.name, highOut.status, "high lane must not be starved")
            assertTrue(highOut.ok)

            holder.join()
        } finally { ex.shutdown() }
    }

    @Test
    fun `a server whose run faults is evicted and replaced on the next invoke`() {
        // healthy() is a weak sensor (always true), so recovery is wired to actual faults: a thrown run drops
        // the (likely broken) warm server; the next invoke for that worktree gets a FRESH one — not a server
        // stuck ERRORing forever.
        val throwOnce = java.util.concurrent.atomic.AtomicBoolean(true)
        val flaky = object : dev.arxael.adapter.BuildAdapter {
            override val name = "flaky"
            override fun open(worktree: Path, outputBase: Path, config: BoxConfig) = object : dev.arxael.adapter.AdapterSession {
                override fun run(spec: InvokeSpec, sink: (String) -> Unit): dev.arxael.adapter.RunResult {
                    if (throwOnce.getAndSet(false)) throw RuntimeException("connection broke")
                    sink("ok"); return dev.arxael.adapter.RunResult(success = true)
                }
                override fun healthy() = true
                override fun close() {}
            }
        }
        val ex = WarmExecutor(cfg(2, 0, 1000), AdapterRegistry.of(flaky), events)
        try {
            val wt = tmp.resolve("flaky-wt").toString()
            val r1 = ex.submit(InvokeSpec(adapter = "flaky", worktree = wt))
            assertEquals(InvokeStatus.ERROR.name, r1.status, "the fault surfaces as ERROR")
            val r2 = ex.submit(InvokeSpec(adapter = "flaky", worktree = wt))
            assertEquals(InvokeStatus.SUCCESS.name, r2.status, "a fresh server recovers the next invoke")
            assertTrue(r1.server != r2.server, "the faulted server (${r1.server}) was replaced (${r2.server})")
        } finally { ex.shutdown() }
    }

    @Test
    fun `heavy concurrent load leaks no permits, loses no work, leaves nothing in flight`() {
        // The trust invariant under load: after a flood of mixed normal/high invokes across several worktrees,
        // every permit is returned, nothing is stuck in flight, and no invoke is silently lost or errored.
        // A permit leak / double-release or a counter drift is invisible single-call but shows up here.
        val maxConcurrent = 8
        val ex = WarmExecutor(cfg(maxConcurrent, reservedHigh = 2, acquireTimeoutMs = 60_000), registry, events)
        try {
            val threads = 24; val perThread = 40
            val success = java.util.concurrent.atomic.AtomicInteger(0)
            val overloaded = java.util.concurrent.atomic.AtomicInteger(0)
            val bad = java.util.concurrent.atomic.AtomicInteger(0)
            (0 until threads).map { t ->
                thread {
                    repeat(perThread) { i ->
                        val out = ex.submit(spec(tmp.resolve("wt${t % 4}").toString(), sleepMs = 1,
                            priority = if (i % 5 == 0) "high" else "normal")) // ~20% high -> reserved lane in play
                        when (out.status) {
                            InvokeStatus.SUCCESS.name -> success.incrementAndGet()
                            InvokeStatus.OVERLOADED.name -> overloaded.incrementAndGet()
                            else -> bad.incrementAndGet()
                        }
                    }
                }
            }.forEach { it.join() }

            val total = threads * perThread
            assertEquals(total, success.get() + overloaded.get() + bad.get(), "every invoke accounted for")
            assertEquals(0, bad.get(), "noop never ERRORs/REJECTs under load")
            assertEquals(0, overloaded.get(), "generous timeout -> callers queue, never false-overload")
            assertEquals(total, success.get(), "no work silently lost")
            assertEquals(0, ex.inFlight(), "no in-flight leak after the flood drains")
            assertEquals(maxConcurrent, ex.snapshot()["permitsAvailable"], "every permit released (no leak/double-release)")
            assertEquals(0, ex.snapshot()["waiting"], "no thread left blocked on a permit")
        } finally { ex.shutdown() }
    }

    @Test
    fun `the live concurrency bound resizes BOTH ways - shrink lowers it, grow raises it`() {
        // start at 3; the governor's lever is setConcurrencyTarget
        val ex = WarmExecutor(cfg(3, 0, 200), registry, events)
        try {
            assertEquals(3, ex.concurrencyTarget())

            // SHRINK to 1: one long build occupies the lone permit -> a second is OVERLOADED
            ex.setConcurrencyTarget(1)
            assertEquals(1, ex.concurrencyTarget())
            val holder = thread { ex.submit(spec(tmp.resolve("s-holder").toString(), sleepMs = 1500)) }
            Thread.sleep(250)
            assertEquals(InvokeStatus.OVERLOADED.name, ex.submit(spec(tmp.resolve("s-2").toString())).status,
                "after shrink to 1, a concurrent build must be shed")
            holder.join()

            // GROW to 3: three concurrent builds now fit (none shed)
            ex.setConcurrencyTarget(3)
            assertEquals(3, ex.concurrencyTarget())
            val hs = (0..1).map { i -> thread { ex.submit(spec(tmp.resolve("g-h$i").toString(), sleepMs = 1200)) } }
            Thread.sleep(250)
            assertEquals(InvokeStatus.SUCCESS.name, ex.submit(spec(tmp.resolve("g-3").toString())).status,
                "after grow to 3, a third concurrent build must be admitted")
            hs.forEach { it.join() }
        } finally { ex.shutdown() }
    }

    @Test
    fun `eviction reclaims orphaned worktree output-bases (bounded disk on a long-running daemon)`() {
        // warmServers=1 so a 2nd distinct worktree forces an eviction (which runs the worktree GC).
        val cfg = cfg(maxConcurrent = 4, reservedHigh = 0, acquireTimeoutMs = 1000).copy(warmServers = 1)
        val ex = WarmExecutor(cfg, registry, events)
        try {
            // An output-base left by a PRIOR worktree that's no longer warm, untouched long ago (past the GC TTL).
            val worktrees = tmp.resolve("worktrees")
            val orphan = worktrees.resolve("deadbeefdeadbeef")
            Files.createDirectories(orphan)
            Files.writeString(orphan.resolve("caches"), "stale")
            Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 20 * 60 * 1000L))
            assertTrue(Files.exists(orphan))

            // Drive two distinct worktrees: the second create pushes the pool over warmServers=1 -> eviction -> GC.
            ex.submit(spec(tmp.resolve("wtA").toString()))
            ex.submit(spec(tmp.resolve("wtB").toString()))

            assertTrue(!Files.exists(orphan), "the orphaned, long-untouched output-base must be reclaimed by eviction GC")
        } finally { ex.shutdown() }
    }

    @Test
    fun `a freshly-touched orphan within the GC TTL is preserved (no deleting an in-flight create)`() {
        val cfg = cfg(maxConcurrent = 4, reservedHigh = 0, acquireTimeoutMs = 1000).copy(warmServers = 1)
        val ex = WarmExecutor(cfg, registry, events)
        try {
            val fresh = tmp.resolve("worktrees").resolve("freshdir00000000")
            Files.createDirectories(fresh) // mtime = now -> within the TTL -> must be spared
            ex.submit(spec(tmp.resolve("wtA").toString()))
            ex.submit(spec(tmp.resolve("wtB").toString()))
            assertTrue(Files.exists(fresh), "a recently-touched dir (possible in-flight create) must NOT be GC'd")
        } finally { ex.shutdown() }
    }

    @Test
    fun `a pinned merge-gate server is exempt from LRU eviction under an agent flood`() {
        // warmServers=1: a flood of agent worktrees would normally evict everything but the freshest. The
        // merge-gate worktree (agentId="merge-gate") is PINNED, so it stays warm -> landings don't go cold.
        val cfg = cfg(maxConcurrent = 4, reservedHigh = 1, acquireTimeoutMs = 1000).copy(warmServers = 1)
        val ex = WarmExecutor(cfg, registry, events)
        try {
            ex.submit(InvokeSpec(adapter = "noop", worktree = tmp.resolve("gate").toString(),
                agentId = "merge-gate", priority = "high"))
            repeat(5) { ex.submit(spec(tmp.resolve("agent$it").toString())) } // would evict an unpinned gate
            assertEquals(1, ex.servers().count { it.pinned }, "the pinned merge-gate server must survive the flood")
            assertTrue(ex.servers().any { it.pinned }, "gate server still warm")
        } finally { ex.shutdown() }
    }
}
