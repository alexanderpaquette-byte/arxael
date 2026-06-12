package dev.arxael.autosize

import dev.arxael.config.BoxConfig
import dev.arxael.config.FootprintStore
import dev.arxael.eventlog.EventLog
import dev.arxael.executor.WarmExecutor
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Background controller that adapts the live concurrency bound (and build workers) to real resource use.
 *
 * Each tick: read available memory (/proc/meminfo) and CPU load (/proc/loadavg); learn the idle baseline
 * (used when nothing is in flight) and the per-build footprint (EWMA of used-above-baseline per in-flight
 * build); ask [AdaptiveSizer] for the next concurrency target within the hard caps; apply it to the executor
 * and derive workers-per-build (C*W ~ cores). This is what keeps density at the box's REAL limit as the
 * agents' project grows — bigger builds raise the learned footprint and lower available memory, so the bound
 * self-corrects DOWN before it can OOM/swap-wedge, and grows back when builds get lighter or the box is bigger.
 *
 * Probe-only + fail-safe: a /proc read failure just skips the tick (the last good bound stands); the loop is
 * a daemon thread and never touches the build hot path.
 */
class AdaptiveGovernor(
    private val executor: WarmExecutor,
    private val config: BoxConfig,
    private val events: EventLog,
) {
    private val caps = AdaptiveSizer.Caps(config.concurrencyFloor, config.concurrencyCeiling)
    @Volatile var footprintMb: Long = config.perBuildFootprintMb; private set
    @Volatile private var baselineMb: Long = 0L // learned on the first idle tick (OS + daemon floor)
    @Volatile private var running = false
    @Volatile private var persistedFootprintMb: Long = config.perBuildFootprintMb
    // Last live sensor readings (for observability; -1 = not sampled yet).
    @Volatile private var lastMemAvailMb: Long = -1L
    @Volatile private var lastCpuLoad1: Double = -1.0
    @Volatile private var lastIoWaitPct: Double = -1.0
    private var loop: Thread? = null

    // Sensor sources, injectable for testing — default to the live /proc readers below. A test sets fakes to
    // drive tick() deterministically without /proc.
    internal var memSource: () -> Mem? = { readMem() }
    internal var load1Source: () -> Double? = { readLoad1() }
    internal var ioWaitSource: () -> Double? = { readIoWaitFraction() }
    internal var completedSource: () -> Long = { executor.completedCount() }

    // H2 goodput-aware saturation signal (hill-climb). The mem/cpu/io sensors are all blind at the goodput peak,
    // so we measure the COMPLETION RATE: after a grow, if goodput didn't improve, cap growth there (soft ceiling)
    // and ease back to the proven level. Re-probed periodically so a changed workload can re-explore.
    @Volatile private var prevCompleted: Long = -1L
    @Volatile private var goodputEwma: Double = -1.0  // smoothed completions/tick; -1 = not seeded yet
    private var probingFromC: Int = -1                // the C we grew FROM and are now evaluating; -1 = not probing
    private var goodputBeforeGrow: Double = 0.0
    private var settleTicks: Int = 0
    @Volatile private var softCeiling: Int = Int.MAX_VALUE // learned no-grow ceiling (the proven peak)
    private var reprobeTicks: Int = 0

    fun start() {
        if (!config.adaptive || running) return
        running = true
        applyWorkers(executor.concurrencyTarget())
        loop = thread(name = "adaptive-governor", isDaemon = true) {
            while (running) {
                try { tick() } catch (e: Exception) { events.emit("governor_error", mapOf("error" to (e.message ?: e.toString()))) }
                try { Thread.sleep(config.governorIntervalMs) } catch (_: InterruptedException) { break }
            }
        }
        events.emit("governor_start", mapOf("caps" to "${caps.floor}..${caps.ceiling}", "intervalMs" to config.governorIntervalMs))
    }

    fun stop() {
        running = false
        loop?.join(2000)
        // persist the latest learned footprint so the next startup is calibrated by history
        FootprintStore.write(config.stateDir, footprintMb)
    }

    /** One control step. Visible for testing; normally driven by the loop. */
    fun tick() {
        val mem = memSource() ?: return
        val usedMb = (mem.totalMb - mem.availMb).coerceAtLeast(0) // a garbled/overcommit sensor must not drive it negative
        val inFlight = executor.inFlight()
        if (inFlight == 0) {
            // Idle floor (OS + daemon) as an EWMA, NOT an unbounded running min: one transient low reading (a
            // page-cache reclaim spiking MemAvailable for a single tick) under `minOf` would PERMANENTLY depress
            // the baseline, inflating every future per-build footprint sample and starving the bound to its floor
            // for the daemon's life. An EWMA self-heals — one dip moves it ≤10% and later normal ticks restore it.
            baselineMb = if (baselineMb <= 0L) usedMb else (baselineMb * 9 + usedMb) / 10
        } else if (baselineMb > 0L) {
            // Only learn the per-build footprint once we have a real idle baseline. If the first tick fires
            // while builds are already running (busy startup), baselineMb is still 0 and `usedMb - 0` would be
            // the WHOLE machine's used memory -> a wildly inflated footprint that starves the bound to the floor.
            footprintMb = AdaptiveSizer.updateFootprint(footprintMb, usedMb - baselineMb, inFlight)
            // persist when the learned footprint has drifted >10% from what's on disk (cheap, throttled)
            if (persistedFootprintMb <= 0 || abs(footprintMb - persistedFootprintMb) * 10 > persistedFootprintMb) {
                FootprintStore.write(config.stateDir, footprintMb)
                persistedFootprintMb = footprintMb
            }
        }
        val current = executor.concurrencyTarget()
        val load1 = load1Source()
        val ioWait = ioWaitSource()
        // Stash the live sensor readings so /metrics + /health can show WHY the governor sized as it did
        // (memory/CPU/IO pressure over time) — otherwise the inputs to every resize are a blind spot.
        lastMemAvailMb = mem.availMb
        lastCpuLoad1 = load1 ?: -1.0
        lastIoWaitPct = ioWait?.let { it * 100.0 } ?: -1.0
        val cpuSaturated = load1?.let { it > config.cores } ?: false
        val ioSaturated = ioWait != null && ioWait > 0.5 // disk ~half-blocked = IO-bound

        // --- H2 goodput hill-climb: measure the completion rate; learn the soft ceiling at the goodput peak. ---
        val completed = completedSource()
        val gpTick = if (prevCompleted < 0) -1.0 else (completed - prevCompleted).toDouble().coerceAtLeast(0.0)
        prevCompleted = completed
        if (gpTick >= 0) goodputEwma = if (goodputEwma < 0) gpTick else GOODPUT_ALPHA * gpTick + (1 - GOODPUT_ALPHA) * goodputEwma
        if (++reprobeTicks >= REPROBE_TICKS) { softCeiling = Int.MAX_VALUE; reprobeTicks = 0 } // periodically re-explore
        // Evaluate a pending grow once it has had time to fill + complete builds: did goodput actually rise?
        if (probingFromC in 0 until current && goodputEwma >= 0 && ++settleTicks >= SETTLE_TICKS) {
            if (goodputEwma <= goodputBeforeGrow * IMPROVE_RATIO) softCeiling = probingFromC // the grow past here didn't pay
            probingFromC = -1; settleTicks = 0
        }
        val goodputStalled = config.governorGoodputSignal && current >= softCeiling

        var target = AdaptiveSizer.nextTarget(
            current, caps, mem.availMb, config.ramHeadroomMb, footprintMb,
            executor.waitingCount(), cpuSaturated, ioSaturated, goodputStalled,
        )
        // Ease back to the proven peak if we'd grown above the learned ceiling (the extra slot didn't add goodput).
        if (config.governorGoodputSignal && current > softCeiling && target >= current) target = current - 1
        when {
            target > current -> { probingFromC = current; goodputBeforeGrow = goodputEwma.coerceAtLeast(0.0); settleTicks = 0 }
            // A RAM-FORCED shrink means conditions changed -> forget the learned ceiling and re-explore.
            target < current && mem.availMb < config.ramHeadroomMb -> { softCeiling = Int.MAX_VALUE; probingFromC = -1 }
        }

        if (target != current) {
            executor.setConcurrencyTarget(target)
            applyWorkers(target)
            events.emit(
                "governor_resize",
                mapOf("from" to current, "to" to target, "workers" to config.liveBuildWorkers.get(),
                    "memAvailMb" to mem.availMb, "footprintMb" to footprintMb,
                    "waiting" to executor.waitingCount(), "cpuSaturated" to cpuSaturated, "ioSaturated" to ioSaturated,
                    "goodput" to goodputEwma, "softCeiling" to (if (softCeiling == Int.MAX_VALUE) -1 else softCeiling)),
            )
        }
    }

    private fun applyWorkers(target: Int) {
        config.liveBuildWorkers.set(AdaptiveSizer.workersFor(config.cores, target))
    }

    /** Live adaptive state for observability (/health). */
    fun snapshot(): Map<String, Any> = mapOf(
        "adaptive" to config.adaptive,
        "concurrencyTarget" to executor.concurrencyTarget(),
        "buildWorkers" to config.liveBuildWorkers.get(),
        "learnedFootprintMb" to footprintMb,
        "caps" to "${caps.floor}..${caps.ceiling}",
        "concurrencyFloor" to caps.floor,     // numeric gauges (the "caps" string above is for humans on /health)
        "concurrencyCeiling" to caps.ceiling,
        "memAvailMb" to lastMemAvailMb,        // live sensors driving the sizing decision (the inputs, over time)
        "cpuLoad1" to lastCpuLoad1,
        "ioWaitPct" to lastIoWaitPct,
        "roDepCache" to (config.liveRoDepCache.get() ?: "none"),
    )

    internal data class Mem(val totalMb: Long, val availMb: Long)

    private fun readMem(): Mem? =
        try { parseMeminfo(Files.readAllLines(Paths.get("/proc/meminfo"))) } catch (_: Exception) { null }

    private fun readLoad1(): Double? =
        try { parseLoad1(Files.readString(Paths.get("/proc/loadavg"))) } catch (_: Exception) { null }

    // Fraction of CPU-time spent waiting on IO since the last tick (delta-based; cumulative counters).
    @Volatile private var prevTotal = -1L
    @Volatile private var prevIoWait = -1L
    private fun readIoWaitFraction(): Double? {
        return try {
            val parsed = parseStatTotalIoWait(Files.readAllLines(Paths.get("/proc/stat"))) ?: return null
            val (total, iowait) = parsed
            val r = if (prevTotal < 0 || total <= prevTotal || iowait < prevIoWait) null // first tick / counter reset
                    else (iowait - prevIoWait).toDouble() / (total - prevTotal).toDouble()
            prevTotal = total; prevIoWait = iowait
            r
        } catch (_: Exception) { null }
    }

    internal companion object {
        // H2 hill-climb tuning. SETTLE_TICKS: ticks to wait after a grow before judging goodput (the new slot must
        // fill + complete a build; at ~3s/tick this is ~15s, covering typical builds). IMPROVE_RATIO: goodput must
        // rise >5% to count the grow as paying. REPROBE_TICKS: forget the learned ceiling every ~2min to re-explore.
        private const val GOODPUT_ALPHA = 0.4
        private const val SETTLE_TICKS = 5
        private const val IMPROVE_RATIO = 1.05
        private const val REPROBE_TICKS = 40

        /** Parse /proc/meminfo lines -> (totalMb, availMb), or null if either is missing. Pure. */
        fun parseMeminfo(lines: List<String>): Mem? {
            fun kb(key: String) = lines.firstOrNull { it.startsWith(key) }
                ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toLong() }
            val total = kb("MemTotal:"); val avail = kb("MemAvailable:")
            return if (total != null && avail != null) Mem(total / 1024, avail / 1024) else null
        }

        /** Parse /proc/loadavg -> the 1-minute load average, or null. Pure. */
        fun parseLoad1(text: String): Double? = text.trim().split(" ").firstOrNull()?.toDoubleOrNull()

        /** Parse /proc/stat's "cpu ..." line -> (total jiffies, iowait jiffies), or null. Pure. */
        fun parseStatTotalIoWait(lines: List<String>): Pair<Long, Long>? {
            val f = lines.firstOrNull { it.startsWith("cpu ") }
                ?.trim()?.split(Regex("\\s+"))?.drop(1)?.mapNotNull { it.toLongOrNull() } ?: return null
            if (f.size < 5) return null
            return f.sum() to f[4]
        }
    }
}
