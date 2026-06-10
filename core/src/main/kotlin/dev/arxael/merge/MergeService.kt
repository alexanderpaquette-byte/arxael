package dev.arxael.merge

import dev.arxael.config.BoxConfig
import dev.arxael.eventlog.EventLog
import dev.arxael.executor.WarmExecutor
import java.nio.file.Files
import java.nio.file.Path

/**
 * Daemon-side lifecycle for the merge orchestrator: one project's shared `main`, the worktrees the
 * orchestrator drives (one integration tree + a pool of async-gate trees), its module dependency graph,
 * and its queue. The agent-runner creates the bare repo holding `main` and registers it once; agents then
 * submit branch-tested PRs. Re-registering swaps the project cleanly (tears the old orchestrator down).
 *
 * Worktrees live under <stateDir>/merge/ and are recreated on each register so a crashed prior run can't
 * leave the orchestrator attached to a half-built tree.
 */
class MergeService(
    private val config: BoxConfig,
    private val executor: WarmExecutor,
    private val events: EventLog,
    private val gateAdapter: String = "gradle",
) {
    data class Registered(val repo: String, val modules: Int, val gateWorktrees: Int, val threshold: Int)

    @Volatile private var orchestrator: MergeOrchestrator? = null
    @Volatile private var registeredRepo: String? = null

    @Synchronized
    fun register(repo: String, forwardDeps: Map<String, Set<String>>, threshold: Int, gateCount: Int): Registered {
        teardown()
        val bare = Path.of(repo)
        require(GitOps.ok(bare, "rev-parse", "--verify", "main")) { "repo '$repo' has no 'main' branch" }

        val base = config.stateDir.resolve("merge")
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        GitOps.git(bare, "worktree", "prune") // drop stale entries pointing at the just-deleted dirs

        val integ = base.resolve("integ")
        require(GitOps.ok(bare, "worktree", "add", "-q", "--detach", integ.toString(), "main")) {
            "could not create integration worktree"
        }
        val gates = (0 until maxOf(1, gateCount)).map { base.resolve("gate$it") }
        gates.forEach {
            // require (not bare git()): a failed gate-worktree add would hand the orchestrator a phantom path
            // whose every async gate errors at runtime (silent throughput loss). Fail register loudly instead.
            require(GitOps.ok(bare, "worktree", "add", "-q", "--detach", it.toString(), "main")) {
                "could not create gate worktree $it"
            }
        }

        // Empty forwardDeps => auto-discover the module graph from the project itself (correct-by-construction).
        // The integration worktree is checked out to main, so probe it. We resolve BOTH the dependency edges
        // and the full set of KNOWN modules. The fast path requires positive knowledge: an unknown module —
        // or a wholly-failed discovery (empty set) — routes BATCHED (sound), never optimistic with a narrow
        // gate. An operator-provided forwardDeps is taken as authoritative (known = everything it mentions).
        val deps: Map<String, Set<String>>
        val knownModules: Set<String>
        var moduleDirs: Map<String, String> = emptyMap() // for change-aware test scoping (discovered graph only)
        if (forwardDeps.isNotEmpty()) {
            deps = forwardDeps
            knownModules = forwardDeps.keys + forwardDeps.values.flatten()
        } else {
            // Bound the gradle probe: a HUNG gradle daemon (no exception, just never returns) would otherwise
            // hold register's @Synchronized monitor forever, deadlocking all future register/shutdown. On
            // timeout we proceed with an empty graph -> everything routes BATCHED (sound, just not incremental).
            val g = timeBounded(config.acquireTimeoutCapMs, ModuleGraphProbe.DiscoveredGraph(emptySet(), emptyMap())) {
                ModuleGraphProbe.discover(integ, config.gradleHome, base.resolve("probe-home"), config.daemonIdleSec)
            }
            deps = g.deps
            knownModules = g.modules
            moduleDirs = g.moduleDirs
            events.emit("merge_graph_discovered", mapOf("modules" to g.modules.size, "edges" to g.deps.values.sumOf { it.size }))
        }
        // Per-worktree homes remove the shared-home cache lock but re-download deps (Maven 429 at scale).
        // Proactively PRE-FILL the daemon-global shared RO cache from this project now (off the request path),
        // so the registered project's per-worktree builds read deps from it instead of all re-downloading on
        // the first burst. The DepCacheConsolidator keeps it topped up from real usage afterward. We warm the
        // SAME shared-deps home that Main wired as liveRoDepCache (Gradle treats GRADLE_RO_DEP_CACHE as
        // read-only, so this writable-warm + read-only-builds split is lock-free). Unless the operator pinned
        // an explicit ARXAEL_RO_DEP_CACHE (then we leave it alone).
        if (config.perWorktreeHome && config.roDepCachePinned == null) {
            // Warm into a SEPARATE seed home, then PUBLISH the deps into the live RO cache via additive copy.
            // Never point the warmer's writable GRADLE_USER_HOME at the RO cache directly: Gradle writes that
            // tree in-place (lock files, in-place metadata rewrites), and a per-worktree build reading it as
            // GRADLE_RO_DEP_CACHE meanwhile could observe a torn file — poisoning every future reader. copyNew
            // is temp+rename, copy-if-absent, so the published deps appear atomically (same as the consolidator).
            val seedHome = config.stateDir.resolve("shared-deps-seed")
            val warmed = timeBounded<Path?>(config.acquireTimeoutCapMs, null) {
                DepCacheWarmer.warm(integ, seedHome, config.gradleHome, config.daemonIdleSec)
            }
            val roCache = config.liveRoDepCache.get()?.let { Path.of(it) }
            if (warmed != null && roCache != null) {
                val published = DepCacheConsolidator.copyNew(warmed.resolve("modules-2"), roCache.resolve("modules-2"))
                events.emit("merge_dep_cache_warmed", mapOf("roDepCache" to roCache.toString(), "published" to published))
            } else {
                events.emit("merge_dep_cache_warm_failed", emptyMap())
            }
        }

        val router = MergeRouter(MergeRouter.reverseDeps(deps), threshold, knownModules)
        val gate = ExecutorMergeGate(executor, gateAdapter)
        // Durability: journal PR lifecycle so a daemon crash mid-flight re-enqueues unfinished PRs on the
        // next register (recovers lost queue entries AND re-gates any unverified optimistic land — soundness).
        // Lives OUTSIDE the worktree base (which register wipes), so it survives a restart.
        // Repo-SCOPED journal: keyed by the repo path so a re-register to a DIFFERENT project can't replay the
        // previous project's branch names against it (they'd just bounce, but it's noise). Same repo across
        // restarts -> same path -> recovery still works (String.hashCode is stable/specified).
        val journal = PrJournal(config.stateDir.resolve("merge-pr-journal-${Integer.toHexString(repo.hashCode())}"))
        val orch = MergeOrchestrator(
            bare, integ, gates, router, gate, events, journal = journal,
            batchCap = config.mergeBatchCap,
            confirmThreshold = config.optimisticConfirmations,
            confirmStore = ConfirmationStore(config.stateDir.resolve("optimistic-confirmations")),
            moduleDirs = moduleDirs,
        ).also {
            it.start()
            it.recover()
        }
        orchestrator = orch
        registeredRepo = repo
        events.emit("merge_register", mapOf("repo" to repo, "modules" to deps.size, "threshold" to threshold, "gates" to gates.size))
        return Registered(repo, deps.size, gates.size, threshold)
    }

    /** Enqueue a PR. Returns false if no project is registered, OR if the orchestrator was torn down between
     *  our read and the call (mid re-register) — the agent then retries rather than the PR being silently lost. */
    fun submit(branch: String, module: String?, agentId: String?): Boolean {
        val o = orchestrator ?: return false // snapshot the volatile once
        return o.submit(PullRequest(branch = branch, module = module, agentId = agentId))
    }

    fun status(): Map<String, Any>? {
        val o = orchestrator ?: return null // snapshot once so repo + snapshot are a consistent pair
        return o.snapshot().toMutableMap().apply { registeredRepo?.let { put("repo", it) } }
    }

    fun isRegistered(): Boolean = orchestrator != null

    @Synchronized
    fun shutdown() = teardown()

    /** Run [block] but give up after [ms] (returns [onTimeout]) so a hung gradle Tooling-API call can't hold
     *  register's monitor forever. The abandoned thread is a daemon (a leaked gradle daemon is reaped later). */
    private fun <T> timeBounded(ms: Long, onTimeout: T, block: () -> T): T {
        val result = java.util.concurrent.atomic.AtomicReference(onTimeout)
        val t = kotlin.concurrent.thread(isDaemon = true, name = "merge-register-probe") {
            runCatching { result.set(block()) }
        }
        t.join(ms)
        if (t.isAlive) events.emit("merge_probe_timeout", mapOf("ms" to ms))
        return result.get()
    }

    private fun teardown() {
        orchestrator?.let { o ->
            o.shutdown()
            registeredRepo?.let { repo ->
                val base = config.stateDir.resolve("merge")
                runCatching {
                    // detach the orchestrator's worktrees so the next register starts clean
                    Files.list(base).use { s -> s.forEach { GitOps.git(Path.of(repo), "worktree", "remove", "--force", it.toString()) } }
                }
            }
        }
        orchestrator = null
        registeredRepo = null
    }
}
