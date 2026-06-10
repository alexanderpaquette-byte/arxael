package dev.arxael.executor

import java.util.concurrent.Semaphore

/**
 * A fair [Semaphore] whose permit ceiling can be raised or lowered at runtime, so the adaptive governor can
 * resize the concurrency bound live. Growing releases permits; shrinking calls the protected reducePermits
 * (driving available permits negative if builds are in flight) — a GRACEFUL shrink: nothing in flight is
 * killed, new acquirers simply wait until enough builds finish to bring the count back under the new target.
 */
class AdjustableSemaphore(initial: Int) : Semaphore(initial, /* fair = */ true) {
    private var configured = initial

    /** Resize the permit ceiling to [target]; returns the applied delta. */
    @Synchronized
    fun adjustTo(target: Int) {
        val delta = target - configured
        when {
            delta > 0 -> release(delta)
            delta < 0 -> reducePermits(-delta)
        }
        configured = target
    }

    @Synchronized
    fun configuredPermits(): Int = configured
}
