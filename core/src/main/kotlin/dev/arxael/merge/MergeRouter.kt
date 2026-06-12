package dev.arxael.merge

/**
 * The routing brain of the auto-route merge strategy (validated in
 * `a merge simulator`, incl. the phase-5 wide-DAG runs).
 *
 * Pure decision logic, deliberately separated from all git / async / executor machinery so it is
 * unit- and mutation-testable on its own. The full orchestrator (merge queue, optimistic land +
 * module-scoped async gate, batched gate-then-land) is a larger productionization effort that builds
 * ON this; the routing decision is the part the campaign proved is first-order, so it lands first.
 *
 * The model: a change to a module can only break the tests of that module + everything that transitively
 * DEPENDS on it (its reverse-dependency closure). Small closure => the optimistic path is safe (a bad PR
 * can poison at most a few modules' gates, no cascade) and gives instant landing. Large closure (a hub
 * module / deep chain) => fall back to the sound batched gate-then-land so main never breaks.
 *
 * Phase-5 finding this encodes: on a realistic wide DAG the closure sizes spread (e.g. 1..12), and routing
 * each PR by its ACTUAL closure sent ~3/4 of PRs down the instant path and the hub-module PRs down the
 * sound path — 0 reverts, main never broke. One knob ([threshold]) trades latency vs main-always-green.
 */
enum class MergeRoute { OPTIMISTIC, BATCHED }

/**
 * Generic over the module-identity type [M]: the fixture uses Int indices, production uses Gradle project
 * paths (":app:core"). Both work — the routing logic only needs a reverse-dependency map and equality.
 */
class MergeRouter<M>(
    /** module -> set of modules that DEPEND on it (reverse-dependency map). */
    private val revDeps: Map<M, Set<M>>,
    /** Closures with size <= this route OPTIMISTIC (fast); larger route BATCHED (sound). */
    val threshold: Int = 4,
    /** The modules the dependency graph actually KNOWS about (the discovered subproject set). The fast path
     *  requires POSITIVE knowledge: a module routes OPTIMISTIC only if it's in this set AND its closure is
     *  small. Anything not known — including the fail-safe case where discovery produced NOTHING and this set
     *  is EMPTY — routes BATCHED (the sound full gate), so a missing/incomplete graph can never silently send
     *  a change down the narrow-gate fast path. `null` = legacy "trust the closure for any module" (tests). */
    private val knownModules: Set<M>? = null,
) {
    /**
     * Modules whose tests a change to [module] could break = [module] + its transitive dependents.
     * Mirrors `affected_closure` in the prototype exactly (BFS over [revDeps]).
     */
    fun affectedClosure(module: M): Set<M> {
        val seen = mutableSetOf(module)
        val stack = ArrayDeque<M>()
        stack.addLast(module)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            for (dependent in revDeps[cur].orEmpty()) {
                if (seen.add(dependent)) stack.addLast(dependent)
            }
        }
        return seen
    }

    /** Route a PR: OPTIMISTIC only with positive knowledge (module is known AND its closure is small);
     *  otherwise BATCHED (sound). An unknown module, or a wholly-unknown graph, never takes the fast path. */
    fun route(module: M): MergeRoute = routeWith(module, threshold)

    /** Route with a caller-supplied threshold instead of the static one — lets the orchestrator drive a DYNAMIC
     *  threshold (H7: conflict-adaptive routing). Same positive-knowledge rule; only the size cutoff varies. */
    fun routeWith(module: M, thr: Int): MergeRoute {
        val known = knownModules == null || module in knownModules
        return if (known && affectedClosure(module).size <= thr) MergeRoute.OPTIMISTIC else MergeRoute.BATCHED
    }

    companion object {
        /**
         * Build the reverse-dependency map from a forward map (module -> the modules it depends ON),
         * the form a build-graph probe naturally produces. `deps[a] = {b}` (a depends on b) becomes
         * `revDeps[b] = {a}` (b's dependent is a). Modules with no edges still appear (empty set).
         */
        fun <M> reverseDeps(deps: Map<M, Set<M>>): Map<M, Set<M>> {
            val rev = HashMap<M, MutableSet<M>>()
            for (m in deps.keys) rev.getOrPut(m) { mutableSetOf() }
            for ((module, on) in deps) {
                for (dep in on) {
                    rev.getOrPut(dep) { mutableSetOf() }.add(module)
                    rev.getOrPut(module) { mutableSetOf() }
                }
            }
            return rev
        }
    }
}
