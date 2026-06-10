package dev.arxael.executor

/**
 * EWMA of recent build durations (ms). The overload (permit-acquire) timeout is derived from this so it
 * tracks how long builds ACTUALLY take: as the agents' project grows and builds lengthen, a caller queued
 * behind running builds is legitimately waiting, not overloaded — a static timeout would falsely shed it.
 *
 * Records only completed builds; until some complete it reports 0, so the executor falls back to the static
 * configured timeout (the adaptive value is always floored at it — never SHORTER than the operator set).
 */
class DurationTracker(private val alpha: Double = 0.3) {
    @Volatile private var ewmaMs: Long = 0

    @Synchronized
    fun record(ms: Long) {
        if (ms <= 0) return
        ewmaMs = if (ewmaMs == 0L) ms else (alpha * ms + (1 - alpha) * ewmaMs).toLong()
    }

    /** Typical recent build duration (ms), or 0 if nothing has completed yet. */
    fun typicalMs(): Long = ewmaMs
}
