package dev.arxael.autosize

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The adaptive controller's decisions. These are the safety-critical cases: shrink under memory pressure
 * (never OOM as the project grows), grow only with demand + measured room + spare CPU, and NEVER cross the
 * operator's hard caps.
 */
class AdaptiveSizerTest {
    private val caps = AdaptiveSizer.Caps(floor = 1, ceiling = 16)

    @Test
    fun `pressure (available below headroom) shrinks hard, even with no demand`() {
        // available 1000 < headroom 2000 -> multiplicative decrease (~quarter)
        val next = AdaptiveSizer.nextTarget(12, caps, memAvailMb = 1000, headroomMb = 2000, perBuildMb = 1500, waiting = 0, cpuSaturated = false)
        assertEquals(9, next) // 12 - max(1, 12/4)=3
    }

    @Test
    fun `demand plus room plus spare CPU grows by one`() {
        // 10 GB free above headroom, footprint 1.5 GB -> room for ~6 -> grow
        val next = AdaptiveSizer.nextTarget(8, caps, memAvailMb = 12_000, headroomMb = 2000, perBuildMb = 1500, waiting = 3, cpuSaturated = false)
        assertEquals(9, next)
    }

    @Test
    fun `does not grow when CPU is saturated even if memory has room and work waits`() {
        val next = AdaptiveSizer.nextTarget(8, caps, memAvailMb = 12_000, headroomMb = 2000, perBuildMb = 1500, waiting = 3, cpuSaturated = true)
        assertEquals(8, next)
    }

    @Test
    fun `does not grow when IO is saturated even if memory and CPU have room`() {
        // slow-disk / network-FS box: plenty of RAM + CPU, but the disk is thrashing -> holding is correct
        val next = AdaptiveSizer.nextTarget(8, caps, memAvailMb = 12_000, headroomMb = 2000, perBuildMb = 1500,
            waiting = 3, cpuSaturated = false, ioSaturated = true)
        assertEquals(8, next)
    }

    @Test
    fun `does not grow without demand`() {
        val next = AdaptiveSizer.nextTarget(8, caps, memAvailMb = 12_000, headroomMb = 2000, perBuildMb = 1500, waiting = 0, cpuSaturated = false)
        assertEquals(8, next)
    }

    @Test
    fun `thin margin (no room for one more build) HOLDS, never a needless shrink`() {
        // only 1 GB above headroom, footprint 1.5 GB -> roomBuilds 0 -> HOLD. Shrinking only happens under
        // actual pressure (memAvail < headroom); shrinking just because there's no room for MORE would ratchet
        // the bound down under steady full load (the bug) — the opposite of using the box.
        val next = AdaptiveSizer.nextTarget(8, caps, memAvailMb = 3000, headroomMb = 2000, perBuildMb = 1500, waiting = 5, cpuSaturated = false)
        assertEquals(8, next)
    }

    @Test
    fun `never exceeds the ceiling or drops below the floor`() {
        assertEquals(16, AdaptiveSizer.nextTarget(16, caps, 100_000, 2000, 1500, waiting = 9, cpuSaturated = false))
        assertEquals(1, AdaptiveSizer.nextTarget(1, caps, 10, 2000, 1500, waiting = 0, cpuSaturated = false))
    }

    @Test
    fun `workersFor couples to cores so C times W tracks core count`() {
        assertEquals(1, AdaptiveSizer.workersFor(cores = 16, concurrency = 16)) // saturated -> 1 each
        assertEquals(2, AdaptiveSizer.workersFor(cores = 16, concurrency = 8))  // half -> 2 each
        assertEquals(16, AdaptiveSizer.workersFor(cores = 16, concurrency = 1)) // single build -> all cores
        assertEquals(16, AdaptiveSizer.workersFor(cores = 16, concurrency = 0)) // guard
    }

    @Test
    fun `adapts BOTH ways - grows into spare capacity, then shrinks when pressure arrives`() {
        var c = 4
        // free memory, demand waiting, spare CPU -> grows toward ceiling
        repeat(5) { c = AdaptiveSizer.nextTarget(c, caps, memAvailMb = 50_000, headroomMb = 2000, perBuildMb = 1500, waiting = 4, cpuSaturated = false) }
        assertEquals(9, c) // 4 -> 9 over 5 additive steps
        // now memory tightens below the floor -> shrinks (multiplicative), without any demand change
        c = AdaptiveSizer.nextTarget(c, caps, memAvailMb = 1000, headroomMb = 2000, perBuildMb = 1500, waiting = 4, cpuSaturated = false)
        assertEquals(7, c) // 9 - max(1, 9/4)=2
        // pressure clears, demand persists -> grows again (symmetric)
        c = AdaptiveSizer.nextTarget(c, caps, memAvailMb = 50_000, headroomMb = 2000, perBuildMb = 1500, waiting = 4, cpuSaturated = false)
        assertEquals(8, c)
    }

    @Test
    fun `at exactly the headroom floor it HOLDS - strict less-than boundary, equal is not pressure`() {
        // memAvail == headroom is NOT "under" the floor: the boundary is strict-less-than. Equal is not
        // pressure, and there's no room to grow -> hold.
        val next = AdaptiveSizer.nextTarget(8, caps, memAvailMb = 2000, headroomMb = 2000, perBuildMb = 1000,
            waiting = 5, cpuSaturated = false, ioSaturated = false)
        assertEquals(8, next) // a '<=' mutant would multiplicative-drop to 6; strict '<' -> hold at 8
    }

    @Test
    fun `an unknown per-build footprint (0) means no room, so it never grows (holds)`() {
        // perBuildMb 0 -> roomBuilds 0 regardless of free memory -> never the grow branch (guards the >0 check)
        val next = AdaptiveSizer.nextTarget(4, caps, memAvailMb = 50_000, headroomMb = 2000, perBuildMb = 0,
            waiting = 9, cpuSaturated = false, ioSaturated = false)
        assertEquals(4, next) // can't size room -> hold (no pressure, so no shrink)
    }

    @Test
    fun `the grow dead-band - room for exactly one build holds, room for two grows`() {
        // roomBuilds == 1 is NOT enough: growing would consume the only slack and force a pressure-shrink next
        // tick (the +1/-1 oscillation). A margin of >=2 is required to grow.
        assertEquals(4, AdaptiveSizer.nextTarget(4, caps, memAvailMb = 3000, headroomMb = 2000, perBuildMb = 1000,
            waiting = 2, cpuSaturated = false, ioSaturated = false)) // room for exactly 1 -> HOLD
        assertEquals(5, AdaptiveSizer.nextTarget(4, caps, memAvailMb = 4000, headroomMb = 2000, perBuildMb = 1000,
            waiting = 2, cpuSaturated = false, ioSaturated = false)) // room for 2 -> grow
    }

    @Test
    fun `footprint EWMA tracks observed per-build usage and ignores idle samples`() {
        // 8 GB used above baseline across 4 builds = 2 GB/build; from a 1.5 GB estimate, EWMA moves toward it
        val updated = AdaptiveSizer.updateFootprint(1500, usedAboveBaselineMb = 8000, inFlight = 4, alpha = 0.5)
        assertEquals(1750, updated) // 0.5*2000 + 0.5*1500
        // no builds in flight -> estimate unchanged
        assertEquals(1500, AdaptiveSizer.updateFootprint(1500, usedAboveBaselineMb = 8000, inFlight = 0))
    }
}
