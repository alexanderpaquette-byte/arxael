package dev.arxael.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Box-scaled configuration.
 *
 * CRITICAL: every concurrency knob scales to the box. A fixed bound would make the
 * whole substrate measure single-build IPC instead of concurrency-density.
 *
 * The core-bound rule plus a load-bearing correction from real operational experience are encoded
 * structurally. The binding constraint at scale is MEMORY, not cores: each warm daemon + active build
 * holds ~1–2 GB, and a memory-tight box maxes RAM and CPU together. So the global bound is the MIN of
 * two box-derived bounds, never a hardcoded number:
 *
 *   coreBound = round(cores * agentsPerCore)                              // the core bound
 *   memBound  = (usableRam - headroom) / perBuildFootprint               // the RAM bound
 *   maxConcurrent = max(1, min(coreBound, memBound))                     // fail closed on whichever binds
 *
 * This is the exact mirror of why the container-per-agent arm collapses in Deliverable #1 (same memory
 * physics, opposite outcome) — so validating [memBound] and finding the container collapse point are the
 * same experiment. Tune [agentsPerCore] AND [perBuildFootprintMb] empirically there.
 *
 * Every value is overridable via ARXAEL_* env vars so the benchmark can sweep them.
 */
data class BoxConfig(
    val cores: Int,
    /** Cap on distinct warm per-worktree servers held at once (~cores). */
    val warmServers: Int,
    /** Agents allowed in flight per core. The empirically-tuned bound is NOT 1. */
    val agentsPerCore: Double,
    /** Core-derived ceiling: round(cores * agentsPerCore). */
    val coreBound: Int,
    /** RAM-derived ceiling: (usableRam - headroom) / perBuildFootprint. */
    val memBound: Int,
    /** Effective global ceiling on concurrent in-flight invocations = min(coreBound, memBound). */
    val maxConcurrent: Int,
    /** Which bound is binding: "cores", "memory", or "override". */
    val bindingConstraint: String,
    /** Total usable RAM the box reports (MB), before headroom. */
    val usableRamMb: Long,
    /** RAM held back for the OS + the daemon JVM itself, never handed to builds (MB). */
    val ramHeadroomMb: Long,
    /** Estimated peak footprint of one warm daemon + active build (MB). Drives [memBound]. */
    val perBuildFootprintMb: Long,
    /** Per-warm-server JVM heap hint (MB), passed to the build tool. */
    val heapPerServerMb: Int,
    /** Parallel worker/fork count handed to the build tool, scaled to cores. */
    val buildWorkers: Int,
    /** Whether the adapter injects the local build cache (--build-cache). Substrate-level (never an
     *  agent arg — the allowlist forbids cache flags). Default on; the benchmark toggles it to isolate
     *  per-worktree build-cache store traffic from the shared-user-home lock ceiling. */
    val buildCache: Boolean,
    /** Give each worktree its OWN Gradle user home, eliminating the shared-user-home cross-process cache
     *  lock that capped concurrency at ~8 builds. Default ON: the lock ceiling is
     *  the dominant density limiter, and the [DepCacheConsolidator][dev.arxael.merge.DepCacheConsolidator]
     *  + a daemon-global read-only dep cache ([liveRoDepCache]) make it safe — per-worktree builds download
     *  into their own home, and freshly-downloaded deps are folded into the shared RO cache so re-downloads
     *  converge to ~zero (no Maven 429 at steady state). Set ARXAEL_PER_WORKTREE_HOME=false to restore the
     *  shared home (the pre-per-worktree default). */
    val perWorktreeHome: Boolean,
    /** Local Gradle installation used by the gradle adapter (offline, deterministic). */
    val gradleHome: Path,
    /** Daemon runtime state root: event log + per-worktree output-bases. */
    val stateDir: Path,
    /** Loopback port for the /invoke HTTP surface. */
    val port: Int,
    /** Watchdog tick interval (ms). */
    val watchdogIntervalMs: Long,
    /** How long a caller waits for a permit before failing closed as OVERLOADED (ms). */
    val acquireTimeoutMs: Long,
    /** Permits reserved for "high"-priority callers (merge-gate tests), so bulk "normal" work (agent
     *  branch-tests) is capped at maxConcurrent-reservedHigh and can never starve landings. 0 = off. */
    val reservedHigh: Int,
    /** Build-tool daemon idle timeout (seconds). Bounds the daemon leak on shutdown (Tooling-API daemons
     *  outlive the executor — default Gradle 3h held GBs of RAM) while staying long enough not to thrash
     *  warm reuse during normal operation. */
    val daemonIdleSec: Long,
    /** Adaptive auto-sizing on/off. When on, a governor adapts the concurrency bound (and build workers)
     *  to LIVE memory pressure within the hard caps below — so density tracks the box's real limit AND
     *  the project's growth, instead of trusting the static startup estimate. Default on. */
    val adaptive: Boolean = true,
    /** HARD floor: the governor never drops concurrency below this (keep making progress). */
    val concurrencyFloor: Int = 1,
    /** HARD ceiling: the governor never raises concurrency above this (the operator's absolute cap).
     *  Defaults to the CPU-derived bound — memory pressure pulls it down, spare capacity grows it back up. */
    val concurrencyCeiling: Int = maxOf(coreBound, maxConcurrent),
    /** Governor sampling interval (ms). */
    val governorIntervalMs: Long = 3000,
    /** Adaptive overload timeout = observed-build-time × this, floored at [acquireTimeoutMs]. So a caller
     *  queued behind genuinely long builds isn't falsely shed as the project (and its builds) grow. */
    val acquireTimeoutMultiplier: Int = 4,
    /** Hard cap on the adaptive overload timeout (ms) — a genuinely wedged box must still fail closed. */
    val acquireTimeoutCapMs: Long = 600_000,
    /** Wall-clock cap on a single build/test RUN (ms). The Gradle Tooling-API `.run()` can block forever if a
     *  build HANGS (a deadlocked test, an unresponsive daemon) — neither throwing nor returning — which would
     *  hold the worktree lock + the executor permit PERMANENTLY, ratcheting capacity down to a silent wedge over
     *  long uptime. On hitting this cap the build is cancelled (Tooling-API token) and the run fails closed
     *  (ERROR -> the executor evicts + recreates the server, releasing the permit). Default 1h — large enough to
     *  NEVER abort a legitimately long build; only a true hang exceeds it. ARXAEL_BUILD_RUN_CAP_MS. */
    val buildRunCapMs: Long = 3_600_000,
    /** How much of the machine to use: 100 = the whole box (default, max performance), 60 = ~60% of cores
     *  and build-available RAM, etc. One dial; ARXAEL_BUDGET_PCT. Exact caps still come from ARXAEL_CORES /
     *  ARXAEL_USABLE_RAM_MB / ARXAEL_MAX_CONCURRENT. */
    val budgetPct: Int = 100,
    /** Verify-then-trust: a module's first N optimistic changes are gated against the FULL project (catches
     *  undeclared coupling the dependency graph can't see) before its narrow declared closure is trusted.
     *  0 = trust the closure immediately. ARXAEL_OPTIMISTIC_CONFIRMATIONS. */
    val optimisticConfirmations: Int = 2,
    /** Merge safety/speed dial: conservative|balanced|fast (ARXAEL_MERGE_MODE). A preset over the knobs below
     *  so a user picks a risk posture without learning each threshold; explicit knobs still win. */
    val mergeMode: String = "balanced",
    /** Default routing threshold a registered project uses when it doesn't pass one: a change whose module
     *  closure is ≤ this lands optimistically (verify-async), else batched. Derived from [mergeMode]
     *  (conservative=0 → all batched, balanced=4, fast=16). */
    val routeThreshold: Int = 4,
    /** Disable the local API token (ARXAEL_NO_AUTH=true). For fully-trusted/dev boxes and the test harnesses;
     *  the daemon then accepts any loopback caller with no X-Arxael-Token header. */
    val noAuth: Boolean = false,
    /** H1c gate-pool backpressure (ARXAEL_GATE_BACKPRESSURE, default on): admit PRs to the optimistic fast path
     *  only up to the async-gate pool's free headroom, routing the overflow through the sound batched gate. Caps
     *  the unverified-on-main backlog and makes auto adapt to build weight + box live. false = legacy unbounded
     *  optimistic admission. */
    val gateBackpressure: Boolean = true,
    /** H1c' backpressure bound (ARXAEL_MAX_VERIFY_LAG_MS, default 25s): the longest projected async-gate verify-lag
     *  (backlog * recentGateMs / gateCapacity) tolerated before optimistic overflow routes to the batched gate.
     *  Higher = more optimistic admission under slow gates; lower = batch sooner. */
    val maxVerifyLagMs: Long = 25_000,
    /** H7 conflict-adaptive routing (ARXAEL_CONFLICT_ADAPTIVE_ROUTING): derive the batch-vs-optimistic threshold from
     *  the measured textual-bounce rate instead of cores. Low conflict -> batch (amortize clean PRs); high conflict ->
     *  optimistic. The routing optimum is driven by conflict rate, not cores. */
    val conflictAdaptiveRouting: Boolean = false,
    /** The "optimistic" closure-threshold H7 ramps to under high conflict (= the fast-mode value). */
    val conflictOptimisticThreshold: Int = 16,
    /** H7' signal tuning (runtime-configurable for A/B + future gate-cost-adaptive crossover). EWMA weight per merge
     *  outcome (ARXAEL_CONFLICT_EWMA_ALPHA, higher = faster-tracking) and the bounce-rate crossover at/above which to
     *  flip batch->optimistic (ARXAEL_HIGH_CONFLICT_RATE). */
    val conflictEwmaAlpha: Double = 0.05,
    val highConflictRate: Double = 0.35,
    /** H12 wall-clock decay (ARXAEL_CONFLICT_DECAY_TAU_S, seconds): also decay the conflict signal over wall-time so
     *  it tracks time-based regime shifts at LOW merge volume (where the per-outcome EWMA lags). 0 = off (legacy). */
    val conflictDecayTauS: Double = 0.0,
    /** H13 optimistic wedge-recovery guard (ARXAEL_OPTIMISTIC_WEDGE_GUARD): re-queue innocent PRs that inherited a
     *  red main instead of cascading-error, so a stuck bad commit degrades gracefully. */
    val optimisticWedgeGuard: Boolean = true,
    /** H15 load-aware routing (ARXAEL_LOAD_AWARE_ROUTING): suppress the optimistic flip when the gate pool is saturated
     *  (optimistic is gate-processing-bound there -> batch amortizes better). */
    val loadAwareRouting: Boolean = false,
    /** H15 saturation fraction (ARXAEL_LOAD_AWARE_FRACTION): suppress the optimistic flip once inFlightGates reaches
     *  ceil(fraction*gateCapacity). 1.0 = legacy "fully saturated" trigger; a lower value routes batch earlier (the
     *  optimistic edge erodes before every worktree is busy). Only consulted when loadAwareRouting=true. */
    val loadAwareFraction: Double = 1.0,
    /** H16 revert-health guard (ARXAEL_REVERT_HEALTH_GUARD): on a revert-conflict (un-revertable bad commit -> main
     *  wedged red), force batch routing for [revertHealthCooldownMs] so new PRs gate-before-landing and the cascade
     *  stops. Keys on the actual wedge signal (revert failed), unlike H15's mis-firing pool-occupancy signal. */
    val revertHealthGuard: Boolean = false,
    val revertHealthCooldownMs: Long = 30_000,
    /** H18 gate-fill routing (ARXAEL_GATE_FILL_ROUTING): route optimistic when (queueDepth+inFlightGates) >=
     *  gateCapacity, else batch. The clean-production routing rule that replaces the inverted conflict/load
     *  proxies; self-scales per box via gateCapacity. Pair with revertHealthGuard for the non-gated wedge edge case. */
    val gateFillRouting: Boolean = false,
    /** H18 fill threshold fraction (ARXAEL_GATE_FILL_FRAC): route opt when pending >= ceil(frac*gateCapacity). 1.0 = pool full. */
    val gateFillFrac: Double = 1.0,
    /** H19 gate-fill hysteresis (ARXAEL_GATE_FILL_HYSTERESIS): sticky regime — leave opt only when pending < thr/2, so
     *  the route doesn't flap when load sits at the threshold. false = legacy binary trigger. */
    val gateFillHysteresis: Boolean = false,
    /** H23 batchCap-aware gate-fill (ARXAEL_BATCHCAP_AWARE): force batch when batchCap > factor*gateCapacity (batch
     *  amortization dominates parallel opt). Closes the large-batchCap mis-route. false = bare gate-fill. */
    val batchCapAware: Boolean = false,
    val batchCapDominanceFactor: Double = 2.0,
    /** H17 route-bandit (ARXAEL_BANDIT_ROUTING): measure net throughput per route over a sliding window and route the
     *  empirical winner (exploring every Kth window). The measure-don't-proxy router; box-adaptive by construction. */
    val banditRouting: Boolean = false,
    val banditWindowMs: Long = 12_000,
    val banditExploreEvery: Int = 4,
    val banditAlpha: Double = 0.5,
    /** H8 conflict-aware batch composition (ARXAEL_DISJOINT_BATCH): when forming a batched gate, pick PRs whose
     *  changed-file sets are pairwise disjoint and defer would-be-conflicting ones to a later batch — removes the
     *  within-batch textual-bounce cascade so high-conflict load drains instead of terminal-bouncing. Off = FIFO. */
    val disjointBatch: Boolean = false,
    /** H2 governor saturation signal (ARXAEL_GOVERNOR_GOODPUT, default on): the adaptive governor learns a soft
     *  concurrency ceiling at the goodput peak (where mem/cpu/io all still read "fine") instead of growing past it.
     *  false = legacy grow-until-a-resource-saturates. */
    val governorGoodputSignal: Boolean = true,
    /** Max PRs the merge orchestrator pulls into ONE batched gate. Higher = a slow real gate amortizes more
     *  PRs per run (the throughput lever on minute-scale builds); bisection keeps a red large batch cheap
     *  (O(k·log n)), so it's safe to raise. ARXAEL_MERGE_BATCH_CAP. */
    val mergeBatchCap: Int = 16,
    /** Operator-pinned read-only dep cache (ARXAEL_RO_DEP_CACHE), or null. When set, the daemon uses it as-is
     *  and does NOT auto-wire its own shared cache / consolidator (the operator owns cache placement). When
     *  null and [perWorktreeHome], the daemon establishes a self-filling shared cache (see Main). */
    val roDepCachePinned: String? = null,
) {
    /** LIVE build-worker count (--max-workers per build). NOT part of the data-class identity — it is runtime
     *  state the adaptive governor mutates as concurrency changes (slots = C*W ~ cores), and the gradle
     *  adapter reads per-run. Seeded from [buildWorkers]. */
    val liveBuildWorkers: java.util.concurrent.atomic.AtomicInteger =
        java.util.concurrent.atomic.AtomicInteger(buildWorkers)

    /** LIVE read-only shared dependency cache (GRADLE_RO_DEP_CACHE), or null. Runtime state — the merge
     *  service warms a seed home and sets it on register, so per-worktree-home builds share already-downloaded
     *  deps (no re-download -> no Maven 429) while keeping per-worktree WRITABLE build caches (no lock). The
     *  gradle adapter reads it per-run. Seeded from ARXAEL_RO_DEP_CACHE if the operator pins one. */
    val liveRoDepCache: java.util.concurrent.atomic.AtomicReference<String?> =
        java.util.concurrent.atomic.AtomicReference(roDepCachePinned)

    /** H4: the gate-pool size to use when the client doesn't pin one (register gateWorktrees<=0). The async-gate
     *  pool is the optimistic-path capacity; the campaign shows merge/build goodput PEAKS at ~4-8 concurrent
     *  builds (NOT linear in cores) and the executor caps in-flight builds at ~maxConcurrent, so a flat default of
     *  4 over-subscribes a 2-core box (thrash) and under-uses a 32-core box. Adapt it: scale with cores, hard-cap
     *  at 8 (the goodput plateau), and never exceed the RAM budget (each gate worktree runs a full build). */
    fun autoGateWorktrees(): Int {
        val byCores = cores.coerceIn(2, 8)
        val byRam = (usableRamMb / perBuildFootprintMb.coerceAtLeast(1)).toInt()
        return minOf(byCores, byRam).coerceAtLeast(1)
    }

    /** Result of resolving the global concurrency bound from the box + config. */
    data class Bounds(
        val coreBound: Int,
        val memBound: Int,
        val maxConcurrent: Int,
        val bindingConstraint: String,
    )

    companion object {
        /** Upper clamp for memBound: prevents a huge-RAM/tiny-footprint box from saturating it to Int.MAX
         *  (which would silently disable the OOM guard). Far above any real box's true concurrency. */
        const val MEM_BOUND_CAP = 100_000L

        /**
         * Pure bound resolution — extracted so it is unit/mutation testable without env.
         * `maxConcurrent = max(1, min(coreBound, memBound))`, with an explicit override winning.
         */
        fun computeBounds(
            cores: Int,
            agentsPerCore: Double,
            usableRamMb: Long,
            ramHeadroomMb: Long,
            perBuildFootprintMb: Long,
            override: Int?,
            budgetPct: Int = 100,
        ): Bounds {
            // budgetPct = how much of the machine to use (100 = the whole box, the default; 60 = ~60% of
            // cores AND of build-available RAM). One simple dial that scales BOTH bounds proportionally.
            val pct = budgetPct.coerceIn(1, 100) / 100.0
            // Defend the arithmetic against degenerate inputs (computeBounds is also called directly by tests /
            // benches): a non-finite or non-positive agentsPerCore makes roundToInt() THROW ("Cannot round NaN
            // value") and crash startup — coerce to the 1.0 default.
            val apc = if (agentsPerCore.isFinite() && agentsPerCore > 0) agentsPerCore else 1.0
            val coreBound = max(1, (cores.coerceAtLeast(0) * apc * pct).roundToInt())
            // Guard the divisor: perBuildFootprintMb=0 (bad ARXAEL_PER_BUILD_MB / stale footprint file) would
            // make this Infinity -> memBound unbounded, defeating the OOM guard the whole bound exists for.
            // Floor at 1 MB so memBound stays finite and conservative.
            val perBuild = maxOf(1L, perBuildFootprintMb)
            // Compute in Long and CLAMP: a huge RAM / tiny footprint makes the quotient exceed 2^31, and
            // (Double).toInt() SATURATES to Int.MAX_VALUE -> an effectively unbounded memBound (the exact state
            // the guard prevents). Cap at a sane ceiling; coreBound is the real binding constraint on any actual
            // box anyway. A negative numerator (headroom > RAM) floors to 0 -> memBound 1 (most conservative).
            val memBound = (((usableRamMb - ramHeadroomMb).coerceAtLeast(0) * pct) / perBuild)
                .toLong().coerceIn(1L, MEM_BOUND_CAP).toInt()
            // A non-positive override (typo / hostile ARXAEL_MAX_CONCURRENT) must not flow through to
            // maxConcurrent<=0 -> a Semaphore(0) that grants nothing and silently wedges the daemon (every
            // /invoke waits out the acquire timeout and returns OVERLOADED). Ignore it and fall back to the
            // computed bound, exactly like every other numeric input. (The max(1,...) below only guarded the
            // computed branch, never the override.)
            val ov = override?.takeIf { it > 0 }
            val maxConcurrent = ov ?: max(1, min(coreBound, memBound))
            val binding = when {
                ov != null -> "override"
                memBound < coreBound -> "memory"
                else -> "cores"
            }
            return Bounds(coreBound, memBound, maxConcurrent, binding)
        }

        /** Total physical RAM in MB, read from /proc/meminfo (Linux). Falls back to the JVM's view. */
        private fun detectTotalRamMb(): Long {
            return try {
                Files.readAllLines(Paths.get("/proc/meminfo"))
                    .firstOrNull { it.startsWith("MemTotal:") }
                    ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                    ?.let { it / 1024 } // kB -> MB
                    ?: (Runtime.getRuntime().maxMemory() / (1024 * 1024))
            } catch (_: Exception) {
                Runtime.getRuntime().maxMemory() / (1024 * 1024)
            }
        }

        /** [getenv] is injectable so the full env-parsing + bound resolution is unit-testable without real
         *  environment variables (default reads the process environment). */
        fun fromEnv(getenv: (String) -> String? = { System.getenv(it) }): BoxConfig {
            fun env(k: String): String? = getenv(k)?.takeIf { it.isNotBlank() }
            // takeIf{>0}: a negative/zero (typo or hostile) numeric env must not silently produce a degenerate
            // box — fall back to the sane default instead. agentsPerCore additionally rejects NaN/Infinity
            // (toDoubleOrNull admits them) so the bound arithmetic can't crash or saturate.
            val cores = env("ARXAEL_CORES")?.toIntOrNull()?.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()
            val agentsPerCore = env("ARXAEL_AGENTS_PER_CORE")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 1.0
            val warmServers = env("ARXAEL_WARM_SERVERS")?.toIntOrNull()?.takeIf { it > 0 } ?: cores

            val stateDir = Paths.get(env("ARXAEL_STATE_DIR") ?: "${System.getProperty("user.home")}/.arxael")

            val usableRamMb = env("ARXAEL_USABLE_RAM_MB")?.toLongOrNull()?.takeIf { it > 0 } ?: detectTotalRamMb()
            // Headroom: keep the larger of 2 GB or 10% of RAM out of builds' reach (OS + daemon JVM).
            val ramHeadroomMb = env("ARXAEL_RAM_HEADROOM_MB")?.toLongOrNull()?.takeIf { it >= 0 } ?: max(2048L, usableRamMb / 10)
            // Per-build footprint: explicit env wins; else the footprint the governor LEARNED on a previous
            // run (so the startup bound is calibrated by history as the project grows); else the 1.5 GB seed.
            val perBuildFootprintMb = maxOf(1L, env("ARXAEL_PER_BUILD_MB")?.toLongOrNull()
                ?: FootprintStore.read(stateDir)
                ?: 1536L) // floor at 1 MB: a 0/negative env or stale footprint file must not blow up memBound

            // Explicit override wins (benchmark sweeps); otherwise fail closed on whichever bound is lower.
            // takeIf{>0} for the same reason as the siblings above: a 0/negative override must not wedge the box
            // (and computeBounds guards it too, so a direct caller is safe regardless).
            val override = env("ARXAEL_MAX_CONCURRENT")?.toIntOrNull()?.takeIf { it > 0 }
            val budgetPct = (env("ARXAEL_BUDGET_PCT")?.toIntOrNull() ?: 100).coerceIn(1, 100)
            val bounds = computeBounds(cores, agentsPerCore, usableRamMb, ramHeadroomMb, perBuildFootprintMb, override, budgetPct)

            val heapPerServerMb = env("ARXAEL_HEAP_PER_SERVER_MB")?.toIntOrNull() ?: 1024
            val buildWorkers = env("ARXAEL_BUILD_WORKERS")?.toIntOrNull() ?: max(1, cores / max(1, warmServers))
            val buildCache = env("ARXAEL_BUILD_CACHE")?.lowercase() != "false" // default on
            val perWorktreeHome = env("ARXAEL_PER_WORKTREE_HOME")?.lowercase() != "false" // default ON
            val gradleHome = Paths.get(env("ARXAEL_GRADLE_HOME") ?: "/opt/gradle/gradle-8.10.2")
            val port = env("ARXAEL_PORT")?.toIntOrNull() ?: 8723
            val watchdogIntervalMs = env("ARXAEL_WATCHDOG_MS")?.toLongOrNull() ?: 2000L
            // Permit-acquire (overload) timeout: defaults to a generous multiple of the watchdog tick,
            // but is independently tunable — legitimate queueing behind tens-of-seconds builds must not
            // be mistaken for overload (the benchmark raises it so OVERLOADED reflects real saturation).
            val acquireTimeoutMs = env("ARXAEL_ACQUIRE_TIMEOUT_MS")?.toLongOrNull() ?: (watchdogIntervalMs * 30)
            // Reserve permits for the high-priority lane (merge-gate landings), but never more than
            // maxConcurrent-1: the normal lane (and the bound) must keep at least one slot, or the reservation
            // is degenerate (you can't reserve every permit). Clamped against the resolved bound.
            val reservedHigh = (env("ARXAEL_RESERVED_HIGH")?.toIntOrNull() ?: 0).coerceIn(0, maxOf(0, bounds.maxConcurrent - 1))
            val daemonIdleSec = env("ARXAEL_DAEMON_IDLE_SEC")?.toLongOrNull() ?: 120L
            val adaptive = env("ARXAEL_ADAPTIVE")?.lowercase() != "false" // default on
            // When a high lane is reserved, the live bound must never shrink to <= reservedHigh, or a normal
            // caller can take the last global permit and starve the reserved merge-gate lane (the exact thing
            // the reservation exists to prevent). So the floor is at least reservedHigh+1 (one protected high
            // slot + one normal), overriding a lower operator-requested floor.
            val minFloor = if (reservedHigh > 0) reservedHigh + 1 else 1
            val concurrencyFloor = maxOf(minFloor, env("ARXAEL_CONCURRENCY_FLOOR")?.toIntOrNull() ?: minFloor)
            // Hard ceiling: operator-set, else the CPU-derived bound (memory pulls it down, slack grows it back).
            val concurrencyCeiling = maxOf(
                concurrencyFloor,
                env("ARXAEL_CONCURRENCY_CEILING")?.toIntOrNull() ?: maxOf(bounds.coreBound, bounds.maxConcurrent),
            )
            val governorIntervalMs = env("ARXAEL_GOVERNOR_MS")?.toLongOrNull() ?: 3000L
            // ARXAEL_MERGE_MODE: a single safety/speed dial mapping onto the routing threshold + verify-then-trust
            // count, so users pick a risk posture without learning each knob. conservative = everything batched
            // (gate-before-land, never an async revert); balanced = today's default; fast = more optimistic
            // routing + skip the full-project warmup. Explicit knobs (below) still win when set.
            val mergeMode = when (env("ARXAEL_MERGE_MODE")?.trim()?.lowercase()) {
                "conservative" -> "conservative"; "fast" -> "fast"; else -> "balanced"
            }
            // AUTO (balanced) is box-adaptive: the MIXED routing (mid threshold) is pathological on scarce
            // cores — its optimistic per-PR gates thrash the cores while the batched half starves (measured:
            // thr=4 was the WORST at cores<=8, ~15/min + 41-87s p50 vs batch's ~35/min + 11.5s). So auto stays
            // ~batch on small boxes and opens the optimistic band only as cores grow enough to absorb those
            // gates (the instant-land sweet spot; old 16/32-core data). conservative/fast = manual overrides.
            val autoThreshold = (cores - 8).coerceIn(0, 64)   // <=8 -> 0 (batch); 16 -> 8; 32 -> 24 (big end validated on a 32-core box)
            val routeThreshold = when (mergeMode) {
                "conservative" -> 0
                "fast" -> maxOf(16, autoThreshold * 2)
                else -> autoThreshold
            }
            val noAuth = env("ARXAEL_NO_AUTH")?.trim()?.lowercase() in setOf("1", "true", "yes")
            // Default ON; only an explicit falsey value disables it (legacy unbounded optimistic admission).
            val gateBackpressure = env("ARXAEL_GATE_BACKPRESSURE")?.trim()?.lowercase() !in setOf("0", "false", "no")
            val maxVerifyLagMs = maxOf(0L, env("ARXAEL_MAX_VERIFY_LAG_MS")?.toLongOrNull() ?: 25_000L)
            val conflictAdaptiveRouting = env("ARXAEL_CONFLICT_ADAPTIVE_ROUTING")?.trim()?.lowercase() in setOf("1", "true", "yes")
            val conflictOptimisticThreshold = maxOf(16, autoThreshold * 2) // the fast-mode (optimistic) closure threshold
            val disjointBatch = env("ARXAEL_DISJOINT_BATCH")?.trim()?.lowercase() in setOf("1", "true", "yes")
            val conflictEwmaAlpha = (env("ARXAEL_CONFLICT_EWMA_ALPHA")?.toDoubleOrNull() ?: 0.05).coerceIn(0.001, 1.0)
            val highConflictRate = (env("ARXAEL_HIGH_CONFLICT_RATE")?.toDoubleOrNull() ?: 0.35).coerceIn(0.01, 0.99)
            val conflictDecayTauS = maxOf(0.0, env("ARXAEL_CONFLICT_DECAY_TAU_S")?.toDoubleOrNull() ?: 0.0)
            val optimisticWedgeGuard = env("ARXAEL_OPTIMISTIC_WEDGE_GUARD")?.trim()?.lowercase() !in setOf("0","false","no") // default ON: free correctness guard (innocent-PR protection on a wedged main), throughput-neutral, helps auto stay robust across edge cases
            val loadAwareRouting = env("ARXAEL_LOAD_AWARE_ROUTING")?.trim()?.lowercase() in setOf("1","true","yes")
            val loadAwareFraction = (env("ARXAEL_LOAD_AWARE_FRACTION")?.toDoubleOrNull() ?: 1.0).coerceIn(0.01, 1.0)
            val revertHealthGuard = env("ARXAEL_REVERT_HEALTH_GUARD")?.trim()?.lowercase() in setOf("1","true","yes")
            val revertHealthCooldownMs = maxOf(0L, env("ARXAEL_REVERT_HEALTH_COOLDOWN_MS")?.toLongOrNull() ?: 30_000L)
            // balanced (=auto) is SELF-TUNING by default — gate-fill load-adaptive routing (H18) with
            // the valley fix (H19 hysteresis) and the batchCap/tiny-pool fix (H23). conservative/fast keep their static
            // thresholds (gateFillRouting stays false for them). Explicit env still overrides either way.
            val gffEnv = env("ARXAEL_GATE_FILL_ROUTING")?.trim()?.lowercase()
            val gateFillRouting = if (gffEnv != null) gffEnv in setOf("1","true","yes") else (mergeMode == "balanced")
            val gateFillFrac = (env("ARXAEL_GATE_FILL_FRAC")?.toDoubleOrNull() ?: 1.0).coerceIn(0.05, 8.0)
            val ghEnv = env("ARXAEL_GATE_FILL_HYSTERESIS")?.trim()?.lowercase()
            val gateFillHysteresis = if (ghEnv != null) ghEnv in setOf("1","true","yes") else (mergeMode == "balanced")
            val bcaEnv = env("ARXAEL_BATCHCAP_AWARE")?.trim()?.lowercase()
            val batchCapAware = if (bcaEnv != null) bcaEnv in setOf("1","true","yes") else (mergeMode == "balanced")
            val batchCapDominanceFactor = (env("ARXAEL_BATCHCAP_DOMINANCE_FACTOR")?.toDoubleOrNull() ?: 2.0).coerceIn(0.5, 16.0)
            val banditRouting = env("ARXAEL_BANDIT_ROUTING")?.trim()?.lowercase() in setOf("1","true","yes")
            val banditWindowMs = maxOf(1000L, env("ARXAEL_BANDIT_WINDOW_MS")?.toLongOrNull() ?: 12_000L)
            val banditExploreEvery = maxOf(0, env("ARXAEL_BANDIT_EXPLORE_EVERY")?.toIntOrNull() ?: 4)
            val banditAlpha = (env("ARXAEL_BANDIT_ALPHA")?.toDoubleOrNull() ?: 0.5).coerceIn(0.01, 1.0)
            val governorGoodputSignal = env("ARXAEL_GOVERNOR_GOODPUT")?.trim()?.lowercase() !in setOf("0", "false", "no")
            val optimisticConfirmations = maxOf(0, env("ARXAEL_OPTIMISTIC_CONFIRMATIONS")?.toIntOrNull()
                ?: when (mergeMode) { "fast" -> 0; else -> 2 })
            val roDepCachePinned = env("ARXAEL_RO_DEP_CACHE")
            val mergeBatchCap = maxOf(1, env("ARXAEL_MERGE_BATCH_CAP")?.toIntOrNull() ?: 16)
            val acquireTimeoutMultiplier = maxOf(1, env("ARXAEL_ACQUIRE_TIMEOUT_MULT")?.toIntOrNull() ?: 4)
            val acquireTimeoutCapMs = env("ARXAEL_ACQUIRE_TIMEOUT_CAP_MS")?.toLongOrNull() ?: 600_000L
            val buildRunCapMs = env("ARXAEL_BUILD_RUN_CAP_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 3_600_000L

            return BoxConfig(
                cores = cores,
                warmServers = warmServers,
                agentsPerCore = agentsPerCore,
                coreBound = bounds.coreBound,
                memBound = bounds.memBound,
                maxConcurrent = bounds.maxConcurrent,
                bindingConstraint = bounds.bindingConstraint,
                usableRamMb = usableRamMb,
                ramHeadroomMb = ramHeadroomMb,
                perBuildFootprintMb = perBuildFootprintMb,
                heapPerServerMb = heapPerServerMb,
                buildWorkers = buildWorkers,
                buildCache = buildCache,
                perWorktreeHome = perWorktreeHome,
                gradleHome = gradleHome,
                stateDir = stateDir,
                port = port,
                watchdogIntervalMs = watchdogIntervalMs,
                acquireTimeoutMs = acquireTimeoutMs,
                reservedHigh = reservedHigh,
                daemonIdleSec = daemonIdleSec,
                adaptive = adaptive,
                concurrencyFloor = concurrencyFloor,
                concurrencyCeiling = concurrencyCeiling,
                governorIntervalMs = governorIntervalMs,
                acquireTimeoutMultiplier = acquireTimeoutMultiplier,
                acquireTimeoutCapMs = acquireTimeoutCapMs,
                buildRunCapMs = buildRunCapMs,
                budgetPct = budgetPct,
                optimisticConfirmations = optimisticConfirmations,
                mergeMode = mergeMode,
                routeThreshold = routeThreshold,
                noAuth = noAuth,
                gateBackpressure = gateBackpressure,
                maxVerifyLagMs = maxVerifyLagMs,
                conflictAdaptiveRouting = conflictAdaptiveRouting,
                conflictOptimisticThreshold = conflictOptimisticThreshold,
                conflictEwmaAlpha = conflictEwmaAlpha,
                highConflictRate = highConflictRate,
                conflictDecayTauS = conflictDecayTauS,
                optimisticWedgeGuard = optimisticWedgeGuard,
                loadAwareRouting = loadAwareRouting,
                loadAwareFraction = loadAwareFraction,
                revertHealthGuard = revertHealthGuard,
                revertHealthCooldownMs = revertHealthCooldownMs,
                gateFillRouting = gateFillRouting,
                gateFillFrac = gateFillFrac,
                gateFillHysteresis = gateFillHysteresis,
                batchCapAware = batchCapAware,
                batchCapDominanceFactor = batchCapDominanceFactor,
                banditRouting = banditRouting,
                banditWindowMs = banditWindowMs,
                banditExploreEvery = banditExploreEvery,
                banditAlpha = banditAlpha,
                disjointBatch = disjointBatch,
                governorGoodputSignal = governorGoodputSignal,
                roDepCachePinned = roDepCachePinned,
                mergeBatchCap = mergeBatchCap,
            )
        }
    }

    fun summary(): String =
        "cores=$cores warmServers=$warmServers agentsPerCore=$agentsPerCore " +
            "maxConcurrent=$maxConcurrent (binding=$bindingConstraint coreBound=$coreBound memBound=$memBound) " +
            "ram=${usableRamMb}MB headroom=${ramHeadroomMb}MB perBuild=${perBuildFootprintMb}MB " +
            "buildWorkers=$buildWorkers buildCache=$buildCache heapPerServerMb=$heapPerServerMb gradleHome=$gradleHome " +
            "stateDir=$stateDir port=$port acquireTimeoutMs=$acquireTimeoutMs perWorktreeHome=$perWorktreeHome " +
            "daemonIdleSec=$daemonIdleSec reservedHigh=$reservedHigh " +
            "adaptive=$adaptive caps=[$concurrencyFloor..$concurrencyCeiling] governorMs=$governorIntervalMs budgetPct=$budgetPct"
}
