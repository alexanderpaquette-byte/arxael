package dev.arxael.autosize

/**
 * Pure controller for the live concurrency bound (AIMD on memory pressure + demand).
 *
 * The box-derived bound (cores, RAM / per-build footprint) is only a STARTING point. As a project grows —
 * bigger builds, more tests, heavier per-build footprint — a FIXED bound silently re-oversubscribes RAM,
 * the exact failure the campaign hit at perBuild=1 GB (CPU/RAM maxed together, swap-wedge). This adapts the
 * bound within hard caps so density tracks the box's REAL limit, on any machine and as the project evolves.
 *
 * The shrink signal is MEASURED, not predicted: available memory below the headroom floor => back off hard
 * (multiplicative decrease) to escape oversubscription fast. The grow signal needs DEMAND (work waiting),
 * a measured memory fit (room for one more build at the learned footprint), AND spare CPU => additive
 * increase by 1. Hard caps [floor, ceiling] are never crossed — the operator's absolute guarantees.
 *
 * Concurrency C and workers-per-build W are COUPLED ([workersFor]): total worker-slots C*W should track the
 * core count (past it, the campaign showed builds thrash). So as the project grows and memory forces C down,
 * each build is handed MORE workers — fewer-but-faster builds keep the box saturated instead of starved.
 */
object AdaptiveSizer {
    data class Caps(val floor: Int, val ceiling: Int)

    /**
     * @param current        current target permits (concurrency C)
     * @param memAvailMb     live available memory (e.g. /proc/meminfo MemAvailable)
     * @param headroomMb     memory that must stay free (OS + daemon JVM); below this = pressure
     * @param perBuildMb     learned/estimated per-build footprint, for the grow-fit check (>0)
     * @param waiting        callers currently queued for a permit (demand signal)
     * @param cpuSaturated   load already at/over cores -> growing C can't help (CPU-bound), so don't
     * @param ioSaturated    disk already saturated (high %iowait) -> more builds just thrash IO, so don't grow
     */
    fun nextTarget(
        current: Int, caps: Caps, memAvailMb: Long, headroomMb: Long, perBuildMb: Long,
        waiting: Int, cpuSaturated: Boolean, ioSaturated: Boolean = false,
        goodputStalled: Boolean = false,
    ): Int {
        fun clamp(n: Int) = n.coerceIn(caps.floor, maxOf(caps.floor, caps.ceiling)) // tolerate floor>ceiling (no throw)
        // PRESSURE: available below the headroom floor -> (about to be) oversubscribed. Multiplicative
        // decrease to escape fast (AIMD): drop ~a quarter, at least 1. This is the ONLY shrink — shrinking
        // merely because the box is fully utilized (no room for MORE) would ratchet the bound down under
        // steady full load, the opposite of what we want.
        if (memAvailMb < headroomMb) return clamp(current - maxOf(1, current / 4))
        // Headroom beyond the floor, expressed in whole builds at the current footprint estimate.
        val roomBuilds = if (perBuildMb > 0) ((memAvailMb - headroomMb) / perBuildMb).toInt() else 0
        // DEMAND + room for the new build PLUS a margin build (a DEAD-BAND of >=2) + spare CPU/IO -> grow by 1.
        // Requiring >=2 (not >=1) means after growing there's still >=1 build of slack, so we don't grow into
        // an immediate pressure-shrink next tick — that +1/-1 thrash was the oscillation bug. Otherwise HOLD.
        // H2 SATURATION SIGNAL: at the goodput peak (~0.5 builds/core) memory/CPU/IO all still read "fine" (CPU
        // ~40%, RAM ok, work queued), so the rule above would grow PAST the peak — p95 inflates 10-40x for zero
        // goodput, marching toward the daemon-collapse cliff. [goodputStalled] is the missing signal the governor
        // measures (growing concurrency stopped raising the completion rate); when set, HOLD instead of growing.
        if (waiting > 0 && roomBuilds >= 2 && !cpuSaturated && !ioSaturated && !goodputStalled) return clamp(current + 1)
        return clamp(current)
    }

    /**
     * Workers per build, derived from concurrency so total slots (C*W) track cores: as C shrinks (heavier
     * project, memory-bound), each build gets more workers. Clamped to [1, cores].
     */
    fun workersFor(cores: Int, concurrency: Int): Int {
        if (concurrency <= 0) return cores.coerceAtLeast(1)
        return (Math.round(cores.toDouble() / concurrency).toInt()).coerceIn(1, cores.coerceAtLeast(1))
    }

    /**
     * Update a per-build footprint estimate (MB) from a live sample via EWMA. [usedAboveBaselineMb] is total
     * used minus the idle baseline; dividing by [inFlight] attributes it per concurrent build. Ignores
     * samples with no builds in flight (nothing to attribute). [alpha] in (0,1]: higher = faster adaptation.
     */
    fun updateFootprint(currentEstimateMb: Long, usedAboveBaselineMb: Long, inFlight: Int, alpha: Double = 0.3): Long {
        if (inFlight <= 0 || usedAboveBaselineMb <= 0) return currentEstimateMb
        val sample = usedAboveBaselineMb / inFlight
        return (alpha * sample + (1 - alpha) * currentEstimateMb).toLong().coerceAtLeast(1)
    }
}
