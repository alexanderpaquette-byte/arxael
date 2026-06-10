package dev.arxael.merge

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The auto-route routing brain (closure sizing + optimistic-vs-batched). These are the cases the merge
 * orchestrator's soundness rides on: route a hub-module PR optimistic and a bad one cascades; route a
 * leaf PR batched and you throw away the instant-landing win. The three graph shapes mirror the campaign's
 * tested topologies (independent / deep chain / wide DAG — docs/ARCHITECTURE.md).
 */
class MergeRouterTest {

    @Test
    fun `reverseDeps inverts a forward dependency map and keeps edgeless modules`() {
        // mod1 depends on mod0; mod2 depends on mod1 (a 3-module chain).
        val rev = MergeRouter.reverseDeps(mapOf(0 to emptySet(), 1 to setOf(0), 2 to setOf(1)))
        assertEquals(setOf(1), rev[0])      // mod0's dependent is mod1
        assertEquals(setOf(2), rev[1])      // mod1's dependent is mod2
        assertEquals(emptySet(), rev[2])    // mod2 (leaf) has no dependents but still appears
    }

    @Test
    fun `reverseDeps makes a dependency-only module a key, and dedups multiple dependents`() {
        // "app" and "web" both depend on "core"; "core" is never a forward key itself.
        val rev = MergeRouter.reverseDeps(mapOf("app" to setOf("core"), "web" to setOf("core")))
        assertEquals(setOf("app", "web"), rev["core"], "core (a dep-only module) becomes a key with both dependents")
        assertEquals(emptySet(), rev["app"])
        // and a change to core sweeps both dependents in its closure
        assertEquals(setOf("core", "app", "web"), MergeRouter(rev).affectedClosure("core"))
    }

    @Test
    fun `independent modules all have closure 1 and route optimistic`() {
        val rev = MergeRouter.reverseDeps(mapOf(0 to emptySet(), 1 to emptySet(), 2 to emptySet()))
        val r = MergeRouter(rev, threshold = 4)
        for (m in 0..2) {
            assertEquals(setOf(m), r.affectedClosure(m))
            assertEquals(MergeRoute.OPTIMISTIC, r.route(m))
        }
    }

    @Test
    fun `linear chain closures grow toward the root`() {
        // forward: mod1->mod0, mod2->mod1, mod3->mod2  =>  a change to mod0 can break everything above it.
        val rev = MergeRouter.reverseDeps(mapOf(0 to emptySet(), 1 to setOf(0), 2 to setOf(1), 3 to setOf(2)))
        val r = MergeRouter(rev, threshold = 2)
        assertEquals(setOf(0, 1, 2, 3), r.affectedClosure(0))   // root: whole chain (size 4)
        assertEquals(setOf(1, 2, 3), r.affectedClosure(1))      // size 3
        assertEquals(setOf(2, 3), r.affectedClosure(2))         // size 2
        assertEquals(setOf(3), r.affectedClosure(3))            // leaf: size 1
        // threshold 2: root + its child route BATCHED (sound); the two leaf-ward modules route OPTIMISTIC.
        assertEquals(MergeRoute.BATCHED, r.route(0))
        assertEquals(MergeRoute.BATCHED, r.route(1))
        assertEquals(MergeRoute.OPTIMISTIC, r.route(2))
        assertEquals(MergeRoute.OPTIMISTIC, r.route(3))
    }

    @Test
    fun `wide DAG routes the hub batched and the leaves optimistic`() {
        // mod1,mod2,mod3 depend on hub mod0; mod4 depends on mod1.
        val rev = MergeRouter.reverseDeps(
            mapOf(0 to emptySet(), 1 to setOf(0), 2 to setOf(0), 3 to setOf(0), 4 to setOf(1)),
        )
        val r = MergeRouter(rev, threshold = 4)
        assertEquals(setOf(0, 1, 2, 3, 4), r.affectedClosure(0))  // hub: size 5 -> over threshold
        assertEquals(setOf(1, 4), r.affectedClosure(1))           // size 2
        assertEquals(setOf(2), r.affectedClosure(2))              // leaf
        assertEquals(MergeRoute.BATCHED, r.route(0))              // hub PR -> sound path
        assertEquals(MergeRoute.OPTIMISTIC, r.route(1))
        assertEquals(MergeRoute.OPTIMISTIC, r.route(2))
        assertEquals(MergeRoute.OPTIMISTIC, r.route(4))
    }

    @Test
    fun `fail-safe - an unknown module or an empty graph routes BATCHED, never the narrow fast path`() {
        val rev = MergeRouter.reverseDeps(mapOf("app" to emptySet(), "core" to emptySet()))
        // knownModules given: a module IN it with a small closure -> optimistic
        val known = MergeRouter(rev, threshold = 4, knownModules = setOf("app", "core"))
        assertEquals(MergeRoute.OPTIMISTIC, known.route("app"))
        // a module NOT in the known set (graph didn't discover it) -> BATCHED (sound), even though its
        // computed closure looks small
        assertEquals(MergeRoute.BATCHED, known.route("mystery"))
        // discovery produced NOTHING (empty known set) -> EVERYTHING batched, regardless of closure
        val blind = MergeRouter(rev, threshold = 4, knownModules = emptySet())
        assertEquals(MergeRoute.BATCHED, blind.route("app"))
        // legacy null = trust the closure for any module (used by the fixture/unit tests)
        assertEquals(MergeRoute.OPTIMISTIC, MergeRouter(rev, threshold = 4, knownModules = null).route("app"))
    }

    @Test
    fun `closure size exactly at the threshold routes optimistic (boundary is inclusive)`() {
        // mod0 has exactly 2 dependents -> closure size 3.
        val rev = MergeRouter.reverseDeps(mapOf(0 to emptySet(), 1 to setOf(0), 2 to setOf(0)))
        assertEquals(3, MergeRouter(rev).affectedClosure(0).size)
        assertEquals(MergeRoute.OPTIMISTIC, MergeRouter(rev, threshold = 3).route(0)) // <= is inclusive
        assertEquals(MergeRoute.BATCHED, MergeRouter(rev, threshold = 2).route(0))    // just over
    }
}
