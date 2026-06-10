package dev.arxael.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The env -> config wiring (fromEnv): which ARXAEL_* var maps to which field, the defaults when unset, and
 * that the budget/caps/adaptive knobs land where the governor + executor read them. Driven by a fake env so
 * it's deterministic (no real environment, no /proc).
 */
class BoxConfigFromEnvTest {
    private fun cfg(env: Map<String, String>) = BoxConfig.fromEnv { env[it] }

    @Test
    fun `hostile or degenerate env values never crash fromEnv and yield bounds in range`() {
        // a bad ARXAEL_* (typo, hostile, stale) must fail closed to a sane box, never throw at startup nor
        // saturate the OOM guard. ARXAEL_USABLE_RAM_MB is pinned so the case is deterministic (no /proc).
        val base = mapOf("ARXAEL_USABLE_RAM_MB" to "64000")
        val hostile = listOf(
            mapOf("ARXAEL_AGENTS_PER_CORE" to "NaN"),
            mapOf("ARXAEL_AGENTS_PER_CORE" to "Infinity"),
            mapOf("ARXAEL_CORES" to "-5"),
            mapOf("ARXAEL_CORES" to "0"),
            mapOf("ARXAEL_CORES" to "notanumber"),
            mapOf("ARXAEL_USABLE_RAM_MB" to "3000000000", "ARXAEL_PER_BUILD_MB" to "1"),
            mapOf("ARXAEL_RAM_HEADROOM_MB" to "999999999"),
            mapOf("ARXAEL_PER_BUILD_MB" to "0"),
            mapOf("ARXAEL_PER_BUILD_MB" to "-100"),
        )
        for (extra in hostile) {
            val c = BoxConfig.fromEnv { (base + extra)[it] } // must not throw
            assertTrue(c.maxConcurrent in 1..100_000, "maxConcurrent in range for $extra, got ${c.maxConcurrent}")
            assertTrue(c.coreBound >= 1 && c.memBound >= 1, "bounds >= 1 for $extra")
        }
    }

    @Test
    fun `defaults are sensible when only cores + RAM are pinned`() {
        val c = cfg(mapOf("ARXAEL_CORES" to "8", "ARXAEL_USABLE_RAM_MB" to "16000"))
        assertEquals(8, c.cores)
        assertEquals(1.0, c.agentsPerCore)
        assertEquals(100, c.budgetPct)
        assertTrue(c.adaptive)
        assertEquals(1, c.concurrencyFloor)
        assertTrue(c.buildCache)
        assertTrue(c.perWorktreeHome) // D8: per-worktree homes are the default (lock-free + self-filling RO cache)
        assertEquals(120L, c.daemonIdleSec)
    }

    @Test
    fun `every knob is read from its env var`() {
        val c = cfg(
            mapOf(
                "ARXAEL_CORES" to "16", "ARXAEL_USABLE_RAM_MB" to "64000", "ARXAEL_AGENTS_PER_CORE" to "2.0",
                "ARXAEL_BUDGET_PCT" to "50", "ARXAEL_PER_WORKTREE_HOME" to "true", "ARXAEL_BUILD_CACHE" to "false",
                "ARXAEL_RESERVED_HIGH" to "3", "ARXAEL_DAEMON_IDLE_SEC" to "60", "ARXAEL_ADAPTIVE" to "false",
                "ARXAEL_CONCURRENCY_FLOOR" to "2", "ARXAEL_CONCURRENCY_CEILING" to "20",
                "ARXAEL_ACQUIRE_TIMEOUT_MULT" to "6", "ARXAEL_PER_BUILD_MB" to "2048",
            ),
        )
        assertEquals(2.0, c.agentsPerCore)
        assertEquals(50, c.budgetPct)
        assertTrue(c.perWorktreeHome)
        assertFalse(c.buildCache)
        assertEquals(3, c.reservedHigh)
        assertEquals(60L, c.daemonIdleSec)
        assertFalse(c.adaptive)
        assertEquals(2, c.concurrencyFloor)
        assertEquals(20, c.concurrencyCeiling)
        assertEquals(6, c.acquireTimeoutMultiplier)
        assertEquals(2048L, c.perBuildFootprintMb)
        // coreBound = round(16 cores * 2.0/core * 0.50 budget) = 16; memBound = (64000-6400)*0.5/2048 = 14 -> binds
        assertEquals(16, c.coreBound)
        assertEquals(14, c.memBound)
        assertEquals(14, c.maxConcurrent)
        assertEquals("memory", c.bindingConstraint)
    }

    @Test
    fun `per-worktree-home can be turned off, and an explicit RO dep cache is captured`() {
        val off = cfg(mapOf("ARXAEL_CORES" to "8", "ARXAEL_USABLE_RAM_MB" to "16000", "ARXAEL_PER_WORKTREE_HOME" to "false"))
        assertFalse(off.perWorktreeHome)
        assertEquals(null, off.roDepCachePinned)
        val pinned = cfg(mapOf("ARXAEL_CORES" to "8", "ARXAEL_USABLE_RAM_MB" to "16000", "ARXAEL_RO_DEP_CACHE" to "/opt/seed/caches"))
        assertEquals("/opt/seed/caches", pinned.roDepCachePinned)
        assertEquals("/opt/seed/caches", pinned.liveRoDepCache.get())
    }

    @Test
    fun `explicit max-concurrent override wins and budget is clamped`() {
        assertEquals(5, cfg(mapOf("ARXAEL_CORES" to "8", "ARXAEL_USABLE_RAM_MB" to "16000", "ARXAEL_MAX_CONCURRENT" to "5")).maxConcurrent)
        // budget over 100 is clamped to 100 (whole machine)
        assertEquals(8, cfg(mapOf("ARXAEL_CORES" to "8", "ARXAEL_USABLE_RAM_MB" to "64000", "ARXAEL_BUDGET_PCT" to "999")).coreBound)
    }
}
