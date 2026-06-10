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
     *  lock that capped concurrency at ~8 builds (Phase 0). Default ON: the lock ceiling is
     *  the dominant density limiter, and the [DepCacheConsolidator][dev.arxael.merge.DepCacheConsolidator]
     *  + a daemon-global read-only dep cache ([liveRoDepCache]) make it safe — per-worktree builds download
     *  into their own home, and freshly-downloaded deps are folded into the shared RO cache so re-downloads
     *  converge to ~zero (no Maven 429 at steady state). Set ARXAEL_PER_WORKTREE_HOME=false to restore the
     *  shared home (the old D4 behavior). */
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
            val optimisticConfirmations = maxOf(0, env("ARXAEL_OPTIMISTIC_CONFIRMATIONS")?.toIntOrNull() ?: 2)
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
