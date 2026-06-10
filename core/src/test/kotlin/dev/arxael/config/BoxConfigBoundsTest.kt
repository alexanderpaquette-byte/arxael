package dev.arxael.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The load-bearing bound logic: maxConcurrent = max(1, min(coreBound, memBound)),
 * with override winning. These are the cases that must never silently regress — a wrong bound
 * either oversubscribes RAM (OOM-wedge) or starves the box.
 */
class BoxConfigBoundsTest {

    @Test
    fun `a non-finite or non-positive agentsPerCore does not crash and yields a sane bound`() {
        // roundToInt() THROWS on NaN ("Cannot round NaN value"); computeBounds must coerce, not crash startup.
        for (apc in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -3.0)) {
            val b = BoxConfig.computeBounds(cores = 8, agentsPerCore = apc,
                usableRamMb = 64_000, ramHeadroomMb = 4_000, perBuildFootprintMb = 1536, override = null)
            assertTrue(b.coreBound in 1..8, "coreBound sane for apc=$apc, got ${b.coreBound}")
            assertTrue(b.maxConcurrent >= 1)
        }
    }

    @Test
    fun `memBound is clamped, never saturated to Int MAX, on huge RAM with a tiny footprint`() {
        val b = BoxConfig.computeBounds(cores = 8, agentsPerCore = 1.0,
            usableRamMb = 3_000_000_000, ramHeadroomMb = 0, perBuildFootprintMb = 1, override = null)
        assertTrue(b.memBound in 1..100_000, "memBound must be clamped, was ${b.memBound}")
        assertEquals(8, b.maxConcurrent, "coreBound binds; memBound must not become Int.MAX and vanish from the min")
    }

    @Test
    fun `headroom exceeding RAM yields the most conservative memBound of 1 (not negative)`() {
        val b = BoxConfig.computeBounds(cores = 8, agentsPerCore = 1.0,
            usableRamMb = 4_000, ramHeadroomMb = 8_000, perBuildFootprintMb = 1536, override = null)
        assertEquals(1, b.memBound)
        assertEquals(1, b.maxConcurrent)
    }

    @Test
    fun `cores bind on a RAM-rich box`() {
        // 32 cores, 252 GB: memBound ~147 >> coreBound 32 -> cores bind (this box).
        val b = BoxConfig.computeBounds(
            cores = 32, agentsPerCore = 1.0,
            usableRamMb = 252_000, ramHeadroomMb = 25_200, perBuildFootprintMb = 1536, override = null,
        )
        assertEquals(32, b.coreBound)
        assertEquals(32, b.maxConcurrent)
        assertEquals("cores", b.bindingConstraint)
    }

    @Test
    fun `memory binds on a RAM-tight box`() {
        // 32 cores but only 16 GB: (16384-2048)/1536 = 9 -> memory binds, NOT cores.
        val b = BoxConfig.computeBounds(
            cores = 32, agentsPerCore = 1.0,
            usableRamMb = 16_384, ramHeadroomMb = 2_048, perBuildFootprintMb = 1536, override = null,
        )
        assertEquals(32, b.coreBound)
        assertEquals(9, b.memBound)
        assertEquals(9, b.maxConcurrent)
        assertEquals("memory", b.bindingConstraint)
    }

    @Test
    fun `override wins and is labelled`() {
        val b = BoxConfig.computeBounds(
            cores = 4, agentsPerCore = 1.0,
            usableRamMb = 8_192, ramHeadroomMb = 2_048, perBuildFootprintMb = 1536, override = 64,
        )
        assertEquals(64, b.maxConcurrent)
        assertEquals("override", b.bindingConstraint)
    }

    @Test
    fun `bound is never below 1 even on a tiny box`() {
        val b = BoxConfig.computeBounds(
            cores = 1, agentsPerCore = 0.0,
            usableRamMb = 1_024, ramHeadroomMb = 2_048, perBuildFootprintMb = 1536, override = null,
        )
        assertEquals(1, b.coreBound)   // max(1, round(1*0.0)) = 1
        assertEquals(1, b.memBound)    // max(1, negative/..) = 1
        assertEquals(1, b.maxConcurrent)
    }

    @Test
    fun `agentsPerCore above 1 raises the core bound`() {
        val b = BoxConfig.computeBounds(
            cores = 16, agentsPerCore = 1.5,
            usableRamMb = 252_000, ramHeadroomMb = 25_200, perBuildFootprintMb = 1536, override = null,
        )
        assertEquals(24, b.coreBound) // round(16*1.5)
    }

    @Test
    fun `budget percent scales BOTH bounds (share the box with other apps)`() {
        // 32 cores, RAM-rich. 100% -> cores bind at 32. 50% -> ~16 cores and half the build-RAM.
        val full = BoxConfig.computeBounds(32, 1.0, 252_000, 25_200, 1536, null, budgetPct = 100)
        assertEquals(32, full.coreBound)
        val half = BoxConfig.computeBounds(32, 1.0, 252_000, 25_200, 1536, null, budgetPct = 50)
        assertEquals(16, half.coreBound)                 // round(32*1.0*0.5)
        assertEquals(73, half.memBound)                  // (252000-25200)*0.5 / 1536
        assertEquals(16, half.maxConcurrent)             // cores still bind after halving both
        assertEquals("cores", half.bindingConstraint)
    }

    @Test
    fun `budget percent is clamped to 1-100 and never zeroes the box`() {
        val over = BoxConfig.computeBounds(8, 1.0, 64_000, 6_400, 1536, null, budgetPct = 500)
        assertEquals(8, over.coreBound)                  // clamped to 100% -> round(8*1.0)
        val under = BoxConfig.computeBounds(8, 1.0, 64_000, 6_400, 1536, null, budgetPct = 0)
        assertEquals(1, under.coreBound)                 // clamped to 1% -> max(1, round(8*0.01))
    }
}
