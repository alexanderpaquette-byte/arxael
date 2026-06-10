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
    private var loop: Thread? = null

    // Sensor sources, injectable for testing — default to the live /proc readers below. A test sets fakes to
    // drive tick() deterministically without /proc.
    internal var memSource: () -> Mem? = { readMem() }
    internal var load1Source: () -> Double? = { readLoad1() }
    internal var ioWaitSource: () -> Double? = { readIoWaitFraction() }

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
        val usedMb = mem.totalMb - mem.availMb
        val inFlight = executor.inFlight()
        if (inFlight == 0) {
            baselineMb = if (baselineMb <= 0L) usedMb else minOf(baselineMb, usedMb) // idle floor = OS + daemon
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
        val cpuSaturated = load1Source()?.let { it > config.cores } ?: false
        val ioSaturated = ioWaitSource().let { it != null && it > 0.5 } // disk ~half-blocked = IO-bound
        val target = AdaptiveSizer.nextTarget(
            current, caps, mem.availMb, config.ramHeadroomMb, footprintMb,
            executor.waitingCount(), cpuSaturated, ioSaturated,
        )
        if (target != current) {
            executor.setConcurrencyTarget(target)
            applyWorkers(target)
            events.emit(
                "governor_resize",
                mapOf("from" to current, "to" to target, "workers" to config.liveBuildWorkers.get(),
                    "memAvailMb" to mem.availMb, "footprintMb" to footprintMb,
                    "waiting" to executor.waitingCount(), "cpuSaturated" to cpuSaturated, "ioSaturated" to ioSaturated),
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
