package dev.arxael.merge

import dev.arxael.eventlog.EventLog
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * The merge orchestrator — the productionized winner from the limit-finding campaign
 * modeled on `a merge simulator` into `core/`.
 *
 * What it does: many trusted agents submit branch-tested PRs; the orchestrator integrates them onto a
 * shared `main`, fast and without conflicts, never leaving main broken. Per PR it AUTO-ROUTES by the
 * change's dependency-closure size ([MergeRouter]):
 *
 *  - SMALL closure  -> OPTIMISTIC: merge onto main and LAND immediately (the branch is already green), then
 *    verify async on a gate worktree testing ONLY the PR's module (module-scoped — a bad PR can poison at
 *    most its own gate, never others' -> no revert cascade). A red gate AUTO-REVERTS the merge from main.
 *    Land latency decouples from gate cost (~instant time-to-land).
 *
 *  - LARGE closure (hub module / deep chain) -> BATCHED gate-then-land: merge a batch onto a detached main,
 *    ONE incremental integration test over the affected closure, land all if green. On a red batch, use
 *    [CulpritAttribution] to bounce only the culprit PRs and re-test the remainder (sound, never bisection
 *    re-queue churn); fall back to per-PR if the failure isn't attributable. NEVER lands a red merge.
 *
 * Soundness rests on PRs arriving branch-tested green (the agent's job — phase-5: branch-gating is the
 * soundness lever) plus the orchestrator's integration gate. All git index/ref mutations are serialized on
 * [gitLock]; only the (CPU/IO-heavy) test calls run concurrently, off the lock.
 */
class MergeOrchestrator(
    private val bare: Path,
    private val integ: Path,
    gateWorktrees: List<Path>,
    private val router: MergeRouter<String>,
    private val gate: MergeGate,
    private val events: EventLog,
    private val batchCap: Int = 8,
    private val journal: PrJournal? = null,
    /** "Verify-then-trust": a module's first [confirmThreshold] optimistic changes are gated against the FULL
     *  project (catches undeclared coupling the dependency graph can't see); after that many clean passes the
     *  narrow declared closure is trusted. 0 = trust the closure immediately (no confirmation). */
    private val confirmThreshold: Int = 0,
    private val confirmStore: ConfirmationStore? = null,
    /** module path -> repo-relative project dir (from [ModuleGraphProbe]); enables change-aware test scoping:
     *  derive the affected modules from a PR's actual diff, and skip the gate for inert (doc-only) changes.
     *  Empty -> change-awareness off (every PR gated as before). */
    private val moduleDirs: Map<String, String> = emptyMap(),
    /** H1c gate-pool backpressure: admit PRs to the optimistic fast path only while the async-gate backlog's
     *  PROJECTED clear-time stays under [maxVerifyLagMs]; route the overflow through the sound synchronous batched
     *  gate. A transiently-full-but-fast-draining pool keeps admitting (preserves the fast-gate win); a pool that
     *  genuinely can't keep up (slow gates -> growing backlog) batches. Adapts to gate cost + box load live, with
     *  no static formula. false = legacy (every small-closure PR lands optimistic regardless of backlog). */
    private val gateBackpressure: Boolean = true,
    /** H1c' backpressure bound: the longest projected verify-lag (backlog * recentGateMs / gateCapacity, ms) we
     *  tolerate before routing overflow to the batched gate. Higher = more optimistic admission. */
    private val maxVerifyLagMs: Long = 25_000,
    /** H7 conflict-adaptive routing: the batch-vs-optimistic optimum is driven by TEXTUAL-CONFLICT rate, not cores —
     *  low conflict (agents on disjoint files) -> BATCH amortizes ~batchCap clean PRs per gate (wins throughput AND
     *  latency); high conflict (agents on hot files) -> OPTIMISTIC, where independent lands dodge batch cascade-
     *  bounces. When on, the effective threshold is derived LIVE from the measured textual-bounce rate (0=batch ..
     *  [conflictOptimisticThreshold]=optimistic), superseding the static cores-derived threshold. */
    private val conflictAdaptive: Boolean = false,
    private val conflictOptimisticThreshold: Int = 16,
    /** H7' tuning, runtime-configurable so the crossover + signal responsiveness can be A/B'd and (future H10) made
     *  gate-cost-adaptive without a rebuild. [conflictEwmaAlpha]: EWMA weight per outcome (higher = faster-tracking,
     *  shorter window). [highConflictRate]: the bounce fraction at/above which to flip batch->optimistic (the crossover). */
    private val conflictEwmaAlpha: Double = CONFLICT_EWMA_ALPHA,
    private val highConflictRate: Double = HIGH_CONFLICT_RATE,
    /** H12 wall-clock decay time-constant (seconds) for the conflict signal. The per-outcome EWMA's responsiveness
     *  scales with THROUGHPUT, so at low merge volume it can't track time-based regime shifts (a low-conflict lull
     *  leaves too few new lands to decay it). >0 also decays the signal toward 0
     *  over wall-time (exp(-dt/tau)), so a quiet/low-conflict lull pulls it back toward batch even without outcomes.
     *  0 = pure per-outcome EWMA (legacy). Inert at high volume (decay between dense outcomes is negligible). */
    private val conflictDecayTauS: Double = 0.0,
    /** H13 optimistic wedge-recovery guard. The batched path has a "main-already-red" guard (C5) that re-queues
     *  innocent PRs instead of blaming them when a gate reds over a scope they didn't break; the OPTIMISTIC path
     *  lacked it — so once one un-revertable bad commit wedged main red, every later PR gated red (inheriting the
     *  broken main), got wrongly reverted/errored, and throughput CASCADED to 0.
     *  With this on, an optimistic red first checks whether the PARENT (main just before this commit) was ALREADY
     *  red over the failed scope; if so the PR is innocent -> re-queue (don't revert), so a stuck bad commit
     *  degrades gracefully instead of collapsing. false = legacy (always revert on red). */
    private val optimisticWedgeGuard: Boolean = true,  // H13 correctness guard: re-queue innocent PRs that inherited an already-wedged main (don't mis-revert/cascade). Throughput-NEUTRAL on clean counts, so default ON for the free correctness, negligible cost in branch-gated prod.
    /** H15 load-aware routing: optimistic's instant-land edge requires a FREE async-gate worktree to parallel-verify.
     *  When the gate pool is SATURATED (inFlightGates >= capacity) optimistic is gate-PROCESSING-bound — no edge over
     *  batch, which amortizes the same limited gates — so route BATCH even at high conflict. Without this, conflict-
     *  adaptive routing flips optimistic into a pool-bound regime where it LOSES (optimistic underperforms batch once
     *  the gate pool is the bottleneck). Self-regulating: optimistic while the pool has room, batch once it fills. false = conflict-
     *  only routing (the H7' behavior). */
    private val loadAwareRouting: Boolean = false,
    /** H15 saturation FRACTION: the optimistic flip is suppressed once inFlightGates >= ceil(fraction * gateCapacity).
     *  1.0 = "fully saturated" (legacy binary trigger). The instant-land edge actually erodes BEFORE every worktree is
     *  busy (new verifies start queueing the moment the pool runs low), so a fraction < 1.0 (e.g. 0.5) routes batch
     *  earlier and may protect the pool-bound mid-scales better. Only consulted when loadAwareRouting=true. */
    private val loadAwareFraction: Double = 1.0,
    /** H8 conflict-aware batch composition: when forming a batch, greedily pick PRs whose changed-file sets are
     *  pairwise DISJOINT (in FIFO order), deferring any PR with a proven file-overlap to a later batch. The
     *  sequential integration merge textual-bounces a PR that conflicts with a sibling already in the batch and
     *  TERMINAL-drops it (the agent must resubmit); composing disjoint batches removes that within-batch cascade,
     *  so the deferred PR instead lands next round onto the updated main (same-file/different-region auto-merges;
     *  only a same-line edit still bounces, now cleanly against main). Strictly >= FIFO for textual handling and
     *  preserves amortization. false = legacy FIFO drain. */
    private val disjointBatch: Boolean = false,
    /** H16 revert-health guard: a revert that CONFLICTS (the bad commit can't be undone — main stays red) is the first
     *  domino of the revert-conflict cascade (optimistic collapses as later optimistic lands pile
     *  onto the un-revertable red main and their reverts conflict in turn). On any such revert-failure, force BATCH
     *  routing for [revertHealthCooldownMs] so new PRs gate-BEFORE-landing instead of stacking more un-revertable lands —
     *  stopping the cascade at the source. Unlike H15 (pool-occupancy, which mis-fires when an opt-heavy load WINS while
     *  saturated), this keys on the ACTUAL wedge signal, so it leaves the healthy optimistic wins untouched.
     *  false = legacy (no cooldown). */
    private val revertHealthGuard: Boolean = false,
    private val revertHealthCooldownMs: Long = 30_000,
    /** H18 gate-fill routing — the clean-production routing rule that REPLACES the mis-signed H7'(conflict)/H15(load)
     *  proxies. Optimistic's PARALLEL async-gating out-throughputs batch's
     *  SERIALIZED integration whenever there's enough pending work to fill the gate pool, and batch wins
     *  only when load can't fill it. So route OPTIMISTIC iff (queueDepth + inFlightGates) >= gateCapacity. The sum
     *  is MODE-ROBUST: under batch a high load backs up the queue; under optimistic it fills the gates — either way the
     *  sum is high at high load, so it can't lock into one mode. Self-scales per box via gateCapacity (the goal). The
     *  H16 revert-health guard, if also on, OVERRIDES to batch when main is wedged (the rare non-gated edge case where
     *  the proxies' correctness concern still applies). false = use the legacy conflict/load path. */
    private val gateFillRouting: Boolean = false,
    /** H18 fill threshold as a FRACTION of gateCapacity. Route optimistic when (queueDepth+inFlightGates) >=
     *  ceil(gateFillFrac * gateCapacity). 1.0 = "pool full" (the original). At moderate load pending HOVERS at
     *  gateCapacity so the 1.0 trigger FLAPS batch/opt -> a mix that falls short of pure-opt; a fraction < 1.0 routes
     *  optimistic at lower backlog to capture the opt win more fully at moderate load (the gate-fill crossover).
     *  Only consulted when gateFillRouting=true. */
    private val gateFillFrac: Double = 1.0,
    /** H19 gate-fill HYSTERESIS (dwell band). The bare gate-fill trigger flips route the instant the live signal
     *  (queueDepth+inFlightGates) crosses gateFillThreshold; when the load SITS AT the threshold (the under-loaded
     *  corner) it FLAPS, so a small batch fraction fragments an otherwise-optimistic flow and auto falls
     *  BELOW both pure modes. Hysteresis makes the regime STICKY: enter optimistic at signal>=gateFillThreshold, but
     *  leave it only when signal drops below gateFillThreshold/2 — so once committed to opt the flow stays opt and
     *  doesn't fragment. Distinct from gateFillFrac (which only SHIFTS the trigger; shifting is
     *  irrelevant — stickiness, not position, is the missing lever). false = exact legacy binary trigger. */
    private val gateFillHysteresis: Boolean = false,
    /** H23 batchCap-AWARE gate-fill. Opt's throughput ceiling ~ gateCapacity (parallel async gates); batch's ~ batchCap
     *  (clean PRs amortized per single gate). When batchCap DOMINATES the pool (batchCap > [batchCapDominanceFactor] *
     *  gateCapacity) batch amortization beats parallel opt at EVERY load, so gate-fill's "deep queue -> opt" signal is
     *  inverted (a deep queue is when batch amortizes MOST). When batchCap dominates, batch decisively out-throughputs
     *  optimistic, yet bare gate-fill routes a partial-opt mix that FRAGMENTS the big batches down to ~opt.
     *  When dominant -> force batch (return 0): no opt fraction, no fragmentation, recovers pure-batch. Crossover factor
     *  ~2 from the (batchCap≈pool: opt wins) / (batchCap≫pool: batch wins) interpolation. false = bare gate-fill (correct at batchCap<=pool). */
    private val batchCapAware: Boolean = false,
    private val batchCapDominanceFactor: Double = 2.0,
    /** H17 route-bandit — the MEASURE-don't-proxy router. Every heuristic (conflict H7', load H15, gate-fill H18) is a
     *  PROXY for "does optimistic out-throughput batch right now", and each has a boundary where it mis-routes (e.g.
     *  H18 routes optimistic even when a tiny gate pool makes optimistic's ceiling lose to batch). The bandit instead
     *  MEASURES the realized net-lands-per-second under each route over a sliding window and routes the empirical
     *  winner, exploring the other arm every [banditExploreEvery] windows to track regime shifts. Box-adaptive by
     *  construction (measures, never assumes) -> the goal, for free, with no per-box knob. false = use the legacy path. */
    private val banditRouting: Boolean = false,
    private val banditWindowMs: Long = 12_000,
    private val banditExploreEvery: Int = 4,
    private val banditAlpha: Double = 0.5,
) {
    private val gitLock = Any()
    private val queue = ConcurrentLinkedQueue<PullRequest>()
    // O(1) backlog count: ConcurrentLinkedQueue.size() is O(n) (and not the hot path's friend). H18 gate-fill routing
    // reads this every routing decision, so maintain it via enqueue/dequeue. Kept balanced at all 6 add/poll sites.
    private val queueDepth = AtomicInteger(0)
    private fun enqueue(pr: PullRequest) { queueDepth.incrementAndGet(); queue.add(pr) }
    private fun dequeue(): PullRequest? = queue.poll()?.also { queueDepth.decrementAndGet() }
    private val confirmations: MutableMap<String, Int> = confirmStore?.load() ?: HashMap()
    private val gatePool = Executors.newFixedThreadPool(maxOf(1, gateWorktrees.size)) { r ->
        Thread(r, "merge-gate").apply { isDaemon = true } // daemon: never block JVM exit if shutdown is missed
    }
    private val freeGateWts = ArrayBlockingQueue<Path>(maxOf(1, gateWorktrees.size)).apply { addAll(gateWorktrees) }
    private val inFlightGates = AtomicInteger(0)
    private val gateCapacity = maxOf(1, gateWorktrees.size) // optimistic-path capacity (H1c backpressure bound)
    // H15 saturation trigger: inFlightGates at/above this routes batch. ceil(fraction*cap), clamped to [1, cap] so
    // fraction=1.0 reproduces the legacy ">= cap" binary trigger and a low fraction still requires >=1 busy gate.
    private val loadAwareSatThreshold = Math.ceil(loadAwareFraction.coerceIn(0.01, 1.0) * gateCapacity).toInt().coerceIn(1, gateCapacity)
    // H18 gate-fill trigger: route opt when pending >= ceil(gateFillFrac*gateCapacity), clamped >=1. Allows >gateCapacity
    // (frac up to ~4) to require a DEEPER backlog before going optimistic, or <1 to go optimistic sooner.
    private val gateFillThreshold = Math.ceil(gateFillFrac.coerceIn(0.05, 8.0) * gateCapacity).toInt().coerceAtLeast(1)
    // H19 hysteresis: current gate-fill regime (false=batch, true=opt), sticky across routing decisions. And the LOW
    // band to leave opt: gateFillThreshold/2 when enabled (a true dwell zone [low, thr)), == gateFillThreshold when
    // disabled (low==hi -> no dwell -> exact legacy binary trigger, zero behavior change when the flag is off).
    private val gateFillState = java.util.concurrent.atomic.AtomicBoolean(false)
    private val gateFillLow = if (gateFillHysteresis) maxOf(1, gateFillThreshold / 2) else gateFillThreshold
    /** H19 pure gate-fill regime decision (internal: visible to MergeOrchestratorTest). Enter the optimistic regime at
     *  signal>=gateFillThreshold; once optimistic, STAY until signal<gateFillLow (the dwell band [gateFillLow,
     *  gateFillThreshold) holds the current regime -> no flap). With hysteresis OFF gateFillLow==gateFillThreshold, so
     *  this reduces EXACTLY to the legacy ">= gateFillThreshold" binary independent of wasOpt. */
    internal fun gateFillNowOpt(signal: Int, wasOpt: Boolean): Boolean =
        if (wasOpt) signal >= gateFillLow else signal >= gateFillThreshold
    /** H23 (internal: visible to MergeOrchestratorTest). True when batchCap dominates the gate pool enough that batch
     *  amortization beats parallel opt regardless of load -> force batch. Off (factor irrelevant) when batchCapAware=false. */
    internal fun batchCapDominates(): Boolean = batchCapAware && batchCap > batchCapDominanceFactor * gateCapacity
    private val recentGateMs = AtomicLong(0) // EWMA of recent async-gate wall-time (ms); 0 until the first completes
    private val processing = AtomicInteger(0) // PRs pulled from the queue but not yet landed/bounced
    @Volatile private var loop: Thread? = null
    @Volatile private var running = false
    @Volatile private var shutDown = false // distinct from running: a torn-down orchestrator rejects new submits

    // ---- metrics (all thread-safe) ----
    private val mSubmitted = AtomicInteger(0)
    private val mLanded = AtomicInteger(0)
    private val mBouncedTextual = AtomicInteger(0)
    private val mBouncedSemantic = AtomicInteger(0)
    private val mReverts = AtomicInteger(0)
    private val mIntegTests = AtomicInteger(0)
    private val mOptLanded = AtomicInteger(0)
    private val mBatchLanded = AtomicInteger(0)
    private val mErrors = AtomicInteger(0) // loop/gate/revert faults — surfaced in snapshot so dark spots show
    private val mOptMainAlreadyRed = AtomicInteger(0) // H13: innocent optimistic PRs re-queued (inherited a red main)
    private val mGatesSkipped = AtomicInteger(0) // gates skipped by change-awareness (inert/doc-only changes)
    private val mBranchMissing = AtomicInteger(0) // PRs whose branch wasn't in the hub (distinct from textual)
    private val ttlNanos = ArrayDeque<Long>() // recent time-to-land samples (bounded; guarded by itself)

    // Per-PR lifecycle, keyed by branch, so an agent can query "did MY branch land?" (GET /merge/pr) instead of
    // racing the aggregate counter. Bounded LRU (access-order false = insertion order) so a long-lived daemon
    // can't grow it without bound; the data already exists in events.jsonl, this just makes it queryable.
    private val prOutcomes = object : LinkedHashMap<String, PrOutcome>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PrOutcome>): Boolean = size > MAX_PR_OUTCOMES
    }
    private val prLock = ReentrantLock()          // guards prOutcomes (an explicit lock + condition instead of
    private val prChanged = prLock.newCondition() // the map's implicit monitor + Object.wait/notify)

    private fun setPr(branch: String, state: String, terminal: Boolean, commit: String? = null, reason: String? = null) {
        prLock.withLock {
            prOutcomes[branch] = PrOutcome(state, terminal, commit, reason)
            prChanged.signalAll() // wake any long-pollers in prStatusWait (instead of a busy spin)
        }
    }

    /** The last-known lifecycle state of [branch], or null if the orchestrator has no record of it. */
    fun prStatus(branch: String): PrOutcome? = prLock.withLock { prOutcomes[branch] }

    /** Block until [branch] reaches a TERMINAL state or [timeoutMs] elapses (long-poll for the agent's land
     *  loop). Returns the last-known outcome (possibly null/non-terminal on timeout). Uses the prOutcomes
     *  monitor's wait/notify — a state change wakes waiters, so there's no busy spin (the prior 25ms-poll loop
     *  burned CPU at ~40Hz per waiter). Callers bound how many threads block here (see MergeService). */
    fun prStatusWait(branch: String, timeoutMs: Long): PrOutcome? {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000
        prLock.withLock {
            while (true) {
                val o = prOutcomes[branch]
                if (o != null && o.terminal) return o
                val remNs = deadlineNs - System.nanoTime()
                if (remNs <= 0L) return o // timed out -> return whatever we have (null or non-terminal)
                prChanged.awaitNanos(remNs)
            }
        }
    }

    /** A submitted branch that the hub repo doesn't have: report it as a DISTINCT, actionable outcome (not the
     *  misleading "textual conflict" a name-not-found merge would otherwise be counted as) and finish it. */
    private fun reportMissingBranch(pr: PullRequest) {
        mBranchMissing.incrementAndGet()
        journal?.done(pr.branch)
        setPr(pr.branch, "missing", true, reason = "branch-not-in-hub")
        events.emit("merge_branch_missing", mapOf(
            "branch" to pr.branch, "agent" to pr.agentId,
            "warn" to "branch not found in the shared hub repo — create it as a worktree off the hub (or push it) before /merge/submit",
        ))
    }

    /** Enqueue a PR. Returns false if this orchestrator has been shut down (e.g. mid re-register) — the caller
     *  then rejects the submit (agent retries) instead of queuing into a stopped loop where it'd never process. */
    fun submit(pr: PullRequest): Boolean {
        if (shutDown) return false
        // Durability FIRST: if we can't journal the SUBMIT, don't accept the PR (don't queue/land something a
        // crash couldn't recover). journal.submit throws on a write failure -> propagates to the caller as a
        // failed submit (the agent retries), rather than silently landing an unrecoverable change.
        journal?.submit(pr)
        mSubmitted.incrementAndGet()
        enqueue(pr)
        setPr(pr.branch, "queued", false)
        events.emit("merge_submit", mapOf("branch" to pr.branch, "module" to pr.module, "agent" to pr.agentId))
        return true
    }

    /**
     * Re-enqueue PRs that a prior run journaled but never finished (crash recovery). Re-enqueuing an
     * optimistically-landed-but-unverified PR re-runs its gate (the re-merge is a no-op), so a crash can't
     * leave an unverified change on main. Call once after [start], before serving new submissions.
     */
    fun recover() {
        val pending = journal?.pending().orEmpty()
        if (pending.isEmpty()) return
        events.emit("merge_recover", mapOf("count" to pending.size))
        // Re-queue directly — these are ALREADY in the journal, so don't re-journal (avoids a redundant write
        // and a journal hiccup aborting recovery). The re-merge of an already-landed PR is a no-op; its gate re-runs.
        pending.forEach { mSubmitted.incrementAndGet(); enqueue(it); setPr(it.branch, "queued", false, reason = "recovered") }
    }

    /** Pull up to [batchCap] queued PRs and process them; returns how many were pulled (0 if idle). */
    fun drainAndProcess(): Int {
        val prs: List<PullRequest> = if (disjointBatch) drainDisjoint() else drainFifo()
        if (prs.isEmpty()) return 0
        // A pulled-but-not-yet-landed PR is in neither the queue nor inFlightGates; count it as "processing"
        // so awaitQuiescent doesn't declare the system idle mid-merge (the window before a land/gate).
        processing.incrementAndGet()
        try { processBatch(prs) } finally { processing.decrementAndGet() }
        return prs.size
    }

    /** Legacy FIFO drain: take up to [batchCap] in arrival order. */
    private fun drainFifo(): List<PullRequest> {
        val prs = ArrayList<PullRequest>(batchCap)
        while (prs.size < batchCap) { prs.add(dequeue() ?: break) }
        return prs
    }

    /** H8 conflict-aware drain: over-fetch up to [batchCap]*[DISJOINT_OVERFETCH] candidates, select a pairwise
     *  file-DISJOINT batch of <= batchCap (FIFO greedy), and re-queue the deferred ones to the tail (they land
     *  next round onto the updated main). Over-fetch is bounded so a long queue can't inflate drain latency or
     *  starve fairness; if everything overlaps we process the FIFO head one-at-a-time (still strictly better than
     *  today's within-batch terminal-bounce of the conflicting siblings). */
    private fun drainDisjoint(): List<PullRequest> {
        val limit = batchCap * DISJOINT_OVERFETCH
        val cand = ArrayList<PullRequest>(limit)
        while (cand.size < limit) { cand.add(dequeue() ?: break) }
        if (cand.isEmpty()) return emptyList()
        val (selected, deferred) = composeDisjointBatch(cand, batchCap) { changedFilesOf(it) }
        deferred.forEach { enqueue(it) } // back to tail; sibling lands first, then this merges onto updated main
        return selected
    }

    /** A PR's changed files vs main (merge-base three-dot diff = stable per branch). Empty when there's no module
     *  graph (change-awareness off) or the diff can't be computed -> H8 treats empty as "no overlap evidence" and
     *  never defers on it (so it's never worse than FIFO). */
    private fun changedFilesOf(pr: PullRequest): Set<String> =
        if (moduleDirs.isEmpty()) emptySet()
        else GitOps.changedFiles(bare, "main...${pr.branch}").toSet()

    /** H7': the closure-size threshold to use NOW. Static (router.threshold = cores-derived) unless conflict-adaptive,
     *  in which case it's driven by the RECENT (windowed/EWMA) textual-bounce rate: below [HIGH_CONFLICT_RATE] -> 0
     *  (BATCH — amortize clean PRs, the safe warmup default); at/above it -> [conflictOptimisticThreshold] (OPTIMISTIC,
     *  where instant parallel lands out-run the batch's bounce cascade). WINDOWED, not cumulative: the
     *  lifetime ratio LAGS — at high agent depth the textual-bounce rate BUILDS over the run (queue deepens -> fuller
     *  batches -> more within-batch collisions), so a cumulative average stays under threshold for most of the run and
     *  never flips (H7 missed the high-conflict optimistic win, staying batch on a rising bounce rate while the regime was really
     *  high-conflict). The EWMA tracks the CURRENT regime and flips within ~1/alpha outcomes of conflict rising. */
    private fun effectiveThreshold(): Int {
        // H17 route-bandit: measure realized throughput per route and route the empirical winner (no proxy). Takes
        // precedence — it's the measure-don't-assume router that the heuristics only approximate.
        if (banditRouting) return banditThreshold()
        // H18 gate-fill routing: optimistic wins when there's enough pending work to fill the gate pool, else batch
        // (the clean-production rule; replaces the inverted H7'/H15 proxies). Mode-robust signal queueDepth+inFlightGates.
        if (gateFillRouting) {
            if (revertHealthGuard) { // H16 correctness override: a wedged (recently un-revertable) main -> force batch
                val last = lastRevertFailNanos.get()
                if (last > 0L && System.nanoTime() - last < revertHealthCooldownMs * 1_000_000L) return 0
            }
            // H23: when batchCap dominates the pool, batch amortization wins at every load -> force batch (no opt
            // fraction to fragment the big batches). Reduces to the bare gate-fill rule when batchCapAware is off.
            if (batchCapDominates()) { gateFillState.set(false); return 0 }
            val signal = queueDepth.get() + inFlightGates.get()
            val nowOpt = gateFillNowOpt(signal, gateFillState.get())
            gateFillState.set(nowOpt)
            return if (nowOpt) conflictOptimisticThreshold else 0
        }
        if (!conflictAdaptive) return router.threshold
        val thr = conflictThresholdWindowed(currentBounceRate(), mLanded.get().toLong() + mBouncedTextual.get().toLong(), conflictOptimisticThreshold, highConflictRate)
        // H15: even at high conflict, optimistic only wins if the async-gate pool has room to parallel-verify. If the
        // pool is saturated (all worktrees gating), optimistic is gate-processing-bound -> batch amortizes better.
        if (thr > 0 && loadAwareRouting && inFlightGates.get() >= loadAwareSatThreshold) return 0
        // H16: a recent revert-conflict means main is wedged red and un-revertable -> stop landing optimistically onto
        // it (each new opt-red's revert would conflict too) -> route batch until the cooldown elapses without a new fail.
        if (thr > 0 && revertHealthGuard) {
            val last = lastRevertFailNanos.get()
            if (last > 0L && System.nanoTime() - last < revertHealthCooldownMs * 1_000_000L) return 0
        }
        return thr
    }

    /** EWMA of recent merge outcomes (1.0 = textual bounce, 0.0 = land); the H7' conflict signal. Updated on every
     *  land/textual-bounce via [recordConflictOutcome]. 0 until the first outcome (treated as low conflict -> batch). */
    private val recentBounceRate = java.util.concurrent.atomic.AtomicReference(0.0)
    private val lastConflictUpdateNanos = AtomicLong(System.nanoTime()) // H12: for the wall-clock decay
    private val lastRevertFailNanos = AtomicLong(0L) // H16: nanoTime of the last revert-conflict (0 = none yet)
    // H17 route-bandit state. banditRoute: 0=batch (the safe bootstrap), 1=optimistic. Rewards are an EWMA of realized
    // net-lands-per-second while that route was active. The window roll is CAS-guarded so exactly one thread rolls.
    private val banditRoute = AtomicInteger(0)
    private val banditWindowStartNanos = AtomicLong(System.nanoTime())
    private val banditWindowStartLanded = AtomicInteger(0)
    private val banditRewardBatch = java.util.concurrent.atomic.AtomicReference(0.0)
    private val banditRewardOpt = java.util.concurrent.atomic.AtomicReference(0.0)
    private val banditWindowCount = AtomicInteger(0)
    /** H17: roll the measurement window if elapsed (CAS so only one thread does it), updating the active route's reward
     *  EWMA from realized net throughput, then pick the next route (exploit argmax, explore the other every Kth window).
     *  Returns the threshold for the chosen route. Cheap on the hot path: a clock read + (rarely) one CAS'd roll. */
    private fun banditThreshold(): Int {
        val now = System.nanoTime()
        val start = banditWindowStartNanos.get()
        val elapsedMs = (now - start) / 1_000_000L
        if (elapsedMs >= banditWindowMs && banditWindowStartNanos.compareAndSet(start, now)) {
            val landedNow = mLanded.get()
            val landedStart = banditWindowStartLanded.getAndSet(landedNow)
            val rate = (landedNow - landedStart).toDouble() / (elapsedMs / 1000.0).coerceAtLeast(0.001) // net lands/sec
            val cur = banditRoute.get()
            (if (cur == 1) banditRewardOpt else banditRewardBatch).updateAndGet { it * (1 - banditAlpha) + rate * banditAlpha }
            val n = banditWindowCount.incrementAndGet()
            // explore the OTHER arm every Kth window (so a regime shift is detected); otherwise exploit the argmax.
            // Exploit picks opt ONLY when it has been MEASURED strictly better (strict >, so the 0.0/0.0 cold-start
            // tie stays on the safe batch bootstrap until exploration actually samples opt). Explore flips every Kth.
            val next = if (banditExploreEvery > 0 && n % banditExploreEvery == 0) 1 - cur
                       else if (banditRewardOpt.get() > banditRewardBatch.get()) 1 else 0
            banditRoute.set(next)
        }
        return if (banditRoute.get() == 1) conflictOptimisticThreshold else 0
    }

    /** The conflict signal NOW. With [conflictDecayTauS]>0 (H12), the per-outcome EWMA is additionally decayed by the
     *  wall-time elapsed since the last outcome, so a low-activity lull pulls it toward 0 (batch) even without new
     *  lands — fixing the low-throughput tracking lag (read-only: doesn't mutate; the decay is folded in at the next
     *  outcome). tau<=0 -> the raw per-outcome EWMA. */
    private fun currentBounceRate(): Double {
        val r = recentBounceRate.get()
        if (conflictDecayTauS <= 0.0 || r <= 0.0) return r
        val dt = (System.nanoTime() - lastConflictUpdateNanos.get()) / 1e9
        return r * Math.exp(-dt / conflictDecayTauS)
    }

    /** Fold [count] identical merge outcomes into [recentBounceRate] (bulk batch-lands fold N at once). EWMA so the
     *  signal reflects the CURRENT conflict regime, not lifetime history (the H7' fix for cumulative lag). When
     *  [conflictDecayTauS]>0 (H12), first apply the wall-clock decay accrued since the last outcome, then the EWMA. */
    private fun recordConflictOutcome(bounced: Boolean, count: Int = 1) {
        if (count <= 0) return
        val target = if (bounced) 1.0 else 0.0
        val decay = if (conflictDecayTauS > 0.0) {
            val now = System.nanoTime()
            Math.exp(-((now - lastConflictUpdateNanos.getAndSet(now)) / 1e9) / conflictDecayTauS)
        } else 1.0
        recentBounceRate.updateAndGet { prev ->
            var r = prev * decay
            repeat(count) { r += conflictEwmaAlpha * (target - r) }
            r
        }
    }

    /** Route a pulled set: small-closure PRs land optimistically; the rest go through the batched gate. */
    fun processBatch(prs: List<PullRequest>) {
        // A branch the hub never received can't be merged by name -> report it distinctly (not as a textual
        // conflict) and drop it; only branches present in the hub proceed.
        val (present, missing) = prs.partition { GitOps.branchExists(bare, it.branch) }
        missing.forEach { reportMissingBranch(it) }
        // Auto-infer the module for a PR that didn't declare one, from its actual diff: if it touches exactly
        // one subproject, route by that module (so a null-module single-module change gets the optimistic fast
        // path + module-scoped gate instead of being forced batched). Multi-module / root changes stay null
        // (routed batched, the sound default). Verify-then-trust still gates the inferred module's first N
        // changes against the full project, so this doesn't weaken soundness.
        val resolved = present.map { pr -> if (pr.module == null) pr.copy(module = inferModule(pr)) else pr }
        // Closure-eligible for the fast path (known module + small reverse-dep closure), using the H7 effective
        // threshold (conflict-adaptive when enabled, else the static cores-derived one).
        val thr = effectiveThreshold()
        val eligible = resolved.filter { it.module != null && router.routeWith(it.module, thr) == MergeRoute.OPTIMISTIC }
        // H1c' BACKPRESSURE: an optimistic land sits unverified on main until its async gate clears. Admit to the
        // fast path only while the CURRENT backlog's projected clear-time stays under maxVerifyLagMs. Projection:
        // clearMs ~= inFlightGates * recentGateMs / gateCapacity. Invert to a backlog cap. Fast gates (small
        // recentGateMs) => large cap => a transiently-full pool keeps admitting (preserves the fast-gate win);
        // slow gates (large recentGateMs) => tiny cap => overflow routes to the sound batched gate. recentGateMs=0
        // (no gate has finished yet) => treat as fast (admit). Never below gateCapacity, so the pool can always
        // fill once. A live signal => auto adapts to gate cost + load with no static formula.
        val optimistic = if (gateBackpressure) {
            val gateMs = recentGateMs.get()
            // recentGateMs==0 => no gate has finished yet: be CONSERVATIVE (admit one pool-full) rather than
            // treating unknown as infinitely fast — otherwise the opening burst floods the pool before the first
            // measurement lands (a slow-gate backlog blowup). Once a gate completes, switch to the lag projection.
            val maxBacklog = if (gateMs <= 0L) gateCapacity.toLong()
                else (maxVerifyLagMs * gateCapacity / gateMs).coerceAtLeast(gateCapacity.toLong())
            eligible.take((maxBacklog.toInt() - inFlightGates.get()).coerceAtLeast(0))
        } else eligible
        val batched = resolved.filterNot { it in optimistic }
        optimistic.forEach { landOptimistic(it) }
        if (batched.isNotEmpty()) processBatched(batched)
    }

    /** Infer a PR's module from its diff: the single subproject it touches, or null if it touches none, many,
     *  a root/shared path, or there's no module graph (then it stays batched — the sound default). */
    private fun inferModule(pr: PullRequest): String? {
        if (moduleDirs.isEmpty()) return null
        val mods = ChangePolicy.affectedModules(GitOps.changedFiles(bare, "main...${pr.branch}"), moduleDirs) ?: return null
        return mods.singleOrNull()
    }

    // ---- optimistic path: land now, verify async, auto-revert ----
    private fun landOptimistic(pr: PullRequest) {
        // Change-aware: what files does this PR actually touch? (computed before the merge moves main)
        val changed = if (moduleDirs.isNotEmpty()) GitOps.changedFiles(bare, "main...${pr.branch}") else emptyList()
        val (landed, advanced) = synchronized(gitLock) {
            val before = GitOps.rev(bare, "main")
            if (!mergeOnto("main", pr)) return            // textual conflict (counted in mergeOnto)
            val head = GitOps.rev(integ, "HEAD")
            GitOps.setBranch(bare, "main", head)          // LAND immediately, atomically with the merge
            head to (head != before)                      // advanced=false => no-op re-merge (PR already on main)
        }
        // Count a land ONLY when it actually MOVED main. The H13 wedge-guard requeue (opt-main-already-red) re-routes
        // an ALREADY-LANDED innocent PR back through here; mergeOnto is then a no-op ("Already up to date" -> true),
        // so without this guard mLanded/mOptLanded would double-count the same physical land and INFLATE
        // merges_per_min in exactly the wedge/high-conflict regime (invalidating the metric). The async re-gate
        // below still runs (that's the point of the requeue) — only the counters are gated on a real advance.
        if (advanced) {
            mLanded.incrementAndGet(); mOptLanded.incrementAndGet(); recordTtl(pr); recordConflictOutcome(bounced = false)
        }
        setPr(pr.branch, "gating", false, commit = landed) // on main, async verify pending
        events.emit("merge_land_optimistic", mapOf("branch" to pr.branch, "module" to pr.module, "commit" to landed))
        // No-test change (docs/text/images only) -> it cannot break a test, so skip the async gate entirely.
        if (moduleDirs.isNotEmpty() && ChangePolicy.isNoTestChange(changed)) {
            mGatesSkipped.incrementAndGet(); journal?.done(pr.branch)
            setPr(pr.branch, "landed", true, commit = landed, reason = "no-test-change")
            events.emit("merge_gate_skipped", mapOf("branch" to pr.branch, "why" to "no-test-change", "files" to changed.size))
            return
        }
        inFlightGates.incrementAndGet()
        try {
            gatePool.submit { asyncGate(pr, landed) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            inFlightGates.decrementAndGet() // pool shutting down -> don't leak the count (keeps H15's signal honest)
        }
    }

    private fun asyncGate(pr: PullRequest, commit: String) {
        val wt = freeGateWts.take()
        try {
            synchronized(gitLock) {
                GitOps.git(wt, "checkout", "-q", "-B", "__gate", commit)
                GitOps.git(wt, "reset", "-q", "--hard", commit)
            }
            mIntegTests.incrementAndGet()
            // Gate scope (verify-then-trust): until this module has been confirmed [confirmThreshold] times,
            // gate the FULL project (null) — that catches a break in ANY module, including undeclared
            // (reflection/resource/runtime) coupling the graph can't see. Once confirmed, narrow to the PR's
            // declared affected CLOSURE (module + dependents) for speed.
            val scope = gateScope(pr)
            val gateT0 = System.nanoTime()
            val conclusive = runCatching { gate.test(wt, scope, "opt-gate-${pr.branch}") }
            // Feed the H1c' backpressure projection: EWMA (alpha 0.3) of recent async-gate wall-time. Only
            // conclusive runs count — an inconclusive/faulted run's duration isn't representative of gate cost.
            if (conclusive.getOrNull()?.conclusive == true) {
                val durMs = (System.nanoTime() - gateT0) / 1_000_000
                recentGateMs.updateAndGet { prev -> if (prev <= 0L) durMs else (prev * 7 + durMs * 3) / 10 }
            }
            val res = conclusive.getOrNull()
            if (res != null && res.green) {
                if (scope == null) recordConfirmation(pr) // a full gate passed -> this module is one step more trusted
                journal?.done(pr.branch)
                setPr(pr.branch, "landed", true, commit = commit)
                events.emit("merge_gate_green", mapOf("branch" to pr.branch, "module" to pr.module))
                return
            }
            if (res != null && res.conclusive) {
                // H13 wedge-recovery guard: did this PR CAUSE the red, or INHERIT it from a prior un-revertable bad
                // commit stuck on main? Gate the parent (main just before this commit) over the failed scope; if the
                // PARENT is already red, this PR is innocent -> re-queue (don't revert/blame it), mirroring the
                // batched C5 guard. Stops the cascade where one wedged bad commit errors every later PR. Best-effort
                // (skipped if no free gate worktree -> mainBaseRed null -> falls through to the legacy revert).
                if (optimisticWedgeGuard) {
                    val parent = runCatching { GitOps.rev(wt, "$commit^1") }.getOrNull()
                    // Gate the parent on the worktree WE ALREADY HOLD (not a polled free one) so the guard ALWAYS
                    // fires — even under load when no gate worktree is free (which is exactly when the wedge cascades).
                    if (parent != null && isRedOn(wt, parent, closureScopeOf(res.failedModules))) {
                        mOptMainAlreadyRed.incrementAndGet()
                        events.emit("merge_opt_main_already_red", mapOf("branch" to pr.branch, "commit" to commit))
                        requeueForRetry(pr, "opt-main-already-red") // innocent: inherited a red main, retry once repaired
                        return
                    }
                }
                // A genuine failure -> revert this commit and bounce (terminal). If the revert CONFLICTS, the
                // bad commit is still on main -> surface loudly (don't pretend it bounced).
                if (revertOrSurface(wt, commit, pr)) {
                    mBouncedSemantic.incrementAndGet()
                    setPr(pr.branch, "reverted", true, commit = commit, reason = "gate-red")
                } else {
                    setPr(pr.branch, "error", true, commit = commit, reason = "revert-conflict-main-still-red")
                }
                journal?.done(pr.branch)
                return
            }
            // INCONCLUSIVE (infra OVERLOADED/ERROR) or a gate FAULT: we could not reach a verdict. Do NOT
            // revert-and-re-merge (reverting a merge poisons a later re-merge of the same branch and flaps
            // main) — instead RE-TEST the SAME already-landed commit, capped. The commit stays put (it was
            // branch-tested green) until we either get a verdict or give up.
            if (conclusive.isFailure) {
                events.emit("merge_gate_error", mapOf("branch" to pr.branch, "error" to conclusive.exceptionOrNull()?.message))
            }
            if (pr.gateAttempts < MAX_GATE_RETRIES) {
                inFlightGates.incrementAndGet() // the re-test is still in-flight; balances this task's finally
                // If the pool is shutting down, submit() throws RejectedExecutionException AFTER we incremented ->
                // roll the count back, else a leaked inFlightGates would make H15 force-batch forever.
                try {
                    gatePool.submit { asyncGate(pr.copy(gateAttempts = pr.gateAttempts + 1), commit) }
                    events.emit("merge_retry", mapOf("branch" to pr.branch, "attempt" to pr.gateAttempts + 1, "why" to "gate-inconclusive"))
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    inFlightGates.decrementAndGet()
                    mErrors.incrementAndGet()
                }
            } else {
                // Exhausted: still couldn't verify. Fail closed — revert the un-gated commit and surface.
                revertOrSurface(wt, commit, pr)
                mErrors.incrementAndGet(); journal?.done(pr.branch)
                setPr(pr.branch, "error", true, commit = commit, reason = "gate-inconclusive-exhausted")
                events.emit("merge_retry_exhausted", mapOf("branch" to pr.branch, "attempts" to pr.gateAttempts))
            }
        } finally {
            freeGateWts.put(wt)
            inFlightGates.decrementAndGet()
        }
    }

    /**
     * Re-queue a BATCHED PR after an inconclusive/fault gate — capped at [MAX_GATE_RETRIES], then give up
     * (terminal, surfaced). Batched PRs aren't landed, so re-queuing simply re-merges from scratch next pass
     * (no revert, so no re-merge poisoning — unlike the optimistic path, which re-tests in place instead).
     */
    private fun requeueForRetry(pr: PullRequest, why: String) {
        // Don't re-queue once shutting down: the drain loop is already stopping, so a re-queued PR would never
        // be drained and awaitQuiescent (waits on an empty queue) would burn the full GATE_DRAIN_MS on every
        // shutdown that caught a batch mid-retry. The journal re-gates it on the next register anyway.
        if (shutDown) {
            mErrors.incrementAndGet(); journal?.done(pr.branch)
            setPr(pr.branch, "error", true, reason = "shutdown-during-$why")
            return
        }
        if (pr.gateAttempts >= MAX_GATE_RETRIES) {
            mErrors.incrementAndGet()
            journal?.done(pr.branch) // terminal: stop retrying; the agent can resubmit later
            setPr(pr.branch, "error", true, reason = "retry-exhausted-$why")
            events.emit("merge_retry_exhausted", mapOf("branch" to pr.branch, "attempts" to pr.gateAttempts, "why" to why))
        } else {
            enqueue(pr.copy(gateAttempts = pr.gateAttempts + 1))
            setPr(pr.branch, "queued", false, reason = "retry-$why")
            events.emit("merge_retry", mapOf("branch" to pr.branch, "attempt" to pr.gateAttempts + 1, "why" to why))
        }
    }

    /** Optimistic gate scope: FULL project (null) until the module is confirmed [confirmThreshold] times,
     *  then the declared closure. Unknown module -> full. */
    private fun gateScope(pr: PullRequest): Set<String>? {
        val m = pr.module ?: return null
        val confirmed = synchronized(confirmations) { (confirmations[m] ?: 0) >= confirmThreshold }
        return if (confirmed) router.affectedClosure(m) else null
    }

    /** A full gate passed for this module -> count it toward trusting its narrow closure (persisted). */
    private fun recordConfirmation(pr: PullRequest) {
        val m = pr.module ?: return
        synchronized(confirmations) {
            val n = confirmations[m] ?: 0
            if (n < confirmThreshold) {
                confirmations[m] = n + 1
                confirmStore?.save(confirmations)
                events.emit("merge_confirm", mapOf("module" to m, "count" to n + 1, "of" to confirmThreshold))
            }
        }
    }

    /**
     * Revert [commit] from main and record it honestly. Returns true iff main was actually reverted. On a
     * revert CONFLICT (the commit can't be cleanly reverted because later commits built on it) the bad commit
     * is STILL ON MAIN — emit a loud `merge_revert_failed` (surfaced on /health + /metrics as an error) rather
     * than the misleading `merge_revert`, so a broken main is never hidden. Auto-recovery of a non-revertable
     * commit needs reverting its dependents too (manual call); this at least makes it visible immediately.
     */
    private fun revertOrSurface(wt: Path, commit: String, pr: PullRequest): Boolean {
        return if (revertFromMain(wt, commit)) {
            // mLanded is NET (decremented on revert); mOptLanded is intentionally GROSS — a cumulative count of
            // optimistic lands EVER (a routing-activity measure), asserted by the orchestrator tests ("all three
            // start by landing optimistically"). So do NOT decrement mOptLanded here. (A no-op RE-merge must not
            // re-count a land; landOptimistic's `advanced` gate handles that, and it applies to gross too —
            // a no-op re-merge is not a distinct land.)
            mReverts.incrementAndGet(); mLanded.decrementAndGet()
            events.emit("merge_revert", mapOf("branch" to pr.branch, "module" to pr.module, "commit" to commit))
            true
        } else {
            mErrors.incrementAndGet()
            lastRevertFailNanos.set(System.nanoTime()) // H16: arm the revert-health cooldown -> route batch until it clears
            events.emit("merge_revert_failed", mapOf(
                "branch" to pr.branch, "module" to pr.module, "commit" to commit,
                "warn" to "revert conflicted — the failing change is STILL ON MAIN; manual revert needed",
            ))
            false
        }
    }

    /**
     * Is [mainBase] itself RED over [scope], independent of anything merged on top? Used by the C5 guard to
     * tell a pre-existing broken main from a fresh batch failure. Returns null if it can't be checked right now
     * (no free gate worktree) — the caller then proceeds as before (no regression). Best-effort and cheap: it
     * only runs when a red batch failed module(s) none of its PRs touched (the suspicious case).
     */
    /** Gate [commit] on the ALREADY-HELD worktree [wt] over [scope]; true if conclusively RED. The H13 guard uses
     *  this (not the worktree-polling [mainBaseRed]) so the parent-red check never depends on a free gate worktree —
     *  it must fire precisely when the pool is busy (the wedge cascade). Costs one extra gate run per optimistic-red,
     *  but reds are the minority and stopping the cascade saves far more. */
    private fun isRedOn(wt: Path, commit: String, scope: Set<String>?): Boolean {
        synchronized(gitLock) {
            GitOps.git(wt, "checkout", "-q", "-B", "__parentred", commit)
            GitOps.git(wt, "reset", "-q", "--hard", commit)
        }
        val r = runCatching { gate.test(wt, scope, "parentred-${commit.take(8)}") }.getOrNull()
        return r != null && r.conclusive && !r.green
    }

    private fun mainBaseRed(mainBase: String, scope: Set<String>?): Boolean? {
        val wt = freeGateWts.poll() ?: return null
        return try {
            synchronized(gitLock) {
                GitOps.git(wt, "checkout", "-q", "-B", "__mainred", mainBase)
                GitOps.git(wt, "reset", "-q", "--hard", mainBase)
            }
            val r = runCatching { gate.test(wt, scope, "mainred-${mainBase.take(8)}") }.getOrNull()
            if (r == null || !r.conclusive) null else !r.green
        } finally { freeGateWts.put(wt) }
    }

    /** Revert [commit] from main using gate worktree [wt]; true if main was updated. Holds [gitLock]. */
    private fun revertFromMain(wt: Path, commit: String): Boolean = synchronized(gitLock) {
        GitOps.git(wt, "checkout", "-q", "-B", "__rev", "main")
        val r = GitOps.git(wt, "revert", "--no-edit", "-m", "1", commit)
        if (r.ok) { GitOps.setBranch(bare, "main", GitOps.rev(wt, "HEAD")); true }
        else { GitOps.git(wt, "revert", "--abort"); false }
    }

    // ---- batched gate-then-land: one incremental test, never lands red, attribute culprits on red ----
    //
    // [inheritedFailScope]: when a parent red batch re-tests a subset (after attribution) or a bisected half,
    // the re-test gate scope must INCLUDE the modules that were red in the parent — otherwise narrowing to the
    // subset's own declared closure can dodge a break caused via UNDECLARED coupling (PR-A breaks module M that
    // PR-B owns; bounce B, re-test A over A's closure which excludes M -> A's red change lands green). Widening
    // the re-test to ⊇ the parent's failed modules keeps the re-test at least as strong as the gate that failed.
    private fun processBatched(prs: List<PullRequest>, inheritedFailScope: Set<String>? = null) {
        val merged = ArrayList<PullRequest>(prs.size)
        var mainBase: String                            // the main commit this batch is merged onto + tested against
        val head = synchronized(gitLock) {
            GitOps.git(integ, "checkout", "-q", "--detach", "main")
            GitOps.git(integ, "reset", "-q", "--hard", "main")
            mainBase = GitOps.rev(integ, "HEAD")
            for (pr in prs) {
                val m = GitOps.git(integ, "merge", "--no-ff", "-m", "merge ${pr.branch}", pr.branch)
                if (m.ok) merged.add(pr) else {
                    GitOps.git(integ, "merge", "--abort"); mBouncedTextual.incrementAndGet(); recordConflictOutcome(bounced = true); journal?.done(pr.branch)
                    setPr(pr.branch, "bounced", true, reason = "textual-conflict")
                    events.emit("merge_bounce_textual", mapOf("branch" to pr.branch))
                }
            }
            if (merged.isEmpty()) return
            GitOps.rev(integ, "HEAD")
        }
        // Change-aware: union of what the merged PRs actually touch (ground truth, not the declared module).
        val changed = if (moduleDirs.isNotEmpty())
            merged.flatMap { GitOps.changedFiles(bare, "main...${it.branch}") }.distinct() else emptyList()
        // The whole batch is inert (docs/text/images only) -> it cannot break a test, so land WITHOUT gating.
        if (moduleDirs.isNotEmpty() && ChangePolicy.isNoTestChange(changed)) {
            if (landBatch(head, mainBase, merged)) {
                mLanded.addAndGet(merged.size); mBatchLanded.addAndGet(merged.size); mGatesSkipped.incrementAndGet(); recordConflictOutcome(bounced = false, count = merged.size)
                merged.forEach { recordTtl(it); journal?.done(it.branch); setPr(it.branch, "landed", true, commit = head, reason = "no-test-change") }
                events.emit("merge_land_batch_skipped", mapOf("count" to merged.size, "why" to "no-test-change"))
            }
            return
        }
        mIntegTests.incrementAndGet()
        val scope = widenScope(batchScope(merged, changed), inheritedFailScope)
        val res = try {
            gate.test(integ, scope, "integ-${merged.first().branch}-x${merged.size}")
        } catch (e: Exception) {
            // A gate fault must not lose the batch (pulled from the queue, not yet landed). Re-enqueue + retry.
            mErrors.incrementAndGet()
            events.emit("merge_batch_error", mapOf("count" to merged.size, "error" to (e.message ?: e.toString())))
            merged.forEach { requeueForRetry(it, "batch-gate-fault") }
            return
        }
        if (res.green) {
            if (landBatch(head, mainBase, merged)) {
                mLanded.addAndGet(merged.size); mBatchLanded.addAndGet(merged.size); recordConflictOutcome(bounced = false, count = merged.size)
                merged.forEach { recordTtl(it); journal?.done(it.branch); setPr(it.branch, "landed", true, commit = head) }
                events.emit("merge_land_batch", mapOf("count" to merged.size, "head" to head))
            }
            return
        }
        if (!res.conclusive) {
            // The gate couldn't actually test (substrate OVERLOADED / infra ERROR) -> retry the batch (capped);
            // do NOT bounce, or an infra blip silently rejects good PRs.
            merged.forEach { requeueForRetry(it, "batch-inconclusive") }
            return
        }
        // conclusive red batch.
        // The modules that were RED in THIS gate — every downstream re-test (remainder or bisected half) must
        // gate over AT LEAST these, so a break carried by undeclared coupling can't escape a narrowed re-test.
        val failScope = closureScopeOf(res.failedModules)
        // Per-PR ground-truth modules from each PR's diff (used by both the C5 already-red guard and culprit
        // attribution). Empty when there's no module graph (then attribution falls back to the declared module).
        val prMods: Map<PullRequest, Set<String>?> =
            if (moduleDirs.isNotEmpty()) merged.associateWith { ChangePolicy.affectedModules(GitOps.changedFiles(bare, "main...${it.branch}"), moduleDirs) }
            else emptyMap()
        // C5 — don't false-bounce innocent PRs onto an ALREADY-red main. If the gate failed module(s) that NO
        // PR in this batch touched, the red is either pre-existing on main (a prior optimistic revert conflicted
        // and left a bad commit) or undeclared coupling FROM this batch. Disambiguate by gating mainBase itself:
        // if mainBase is red over the failed scope, the breakage pre-existed -> these PRs are innocent, so
        // RE-QUEUE them (don't bounce — bouncing would amplify one broken main into a stream of false culprits,
        // and never lands red since the batch gate is red). If mainBase is green, it IS the batch's coupling ->
        // fall through to attribution/bisection (the widened re-test isolates it).
        if (prMods.isNotEmpty() && res.failedModules.isNotEmpty() && merged.all { prMods[it] != null } &&
            merged.none { (prMods[it] ?: emptySet()).any { m -> m in res.failedModules } } &&
            mainBaseRed(mainBase, failScope) == true
        ) {
            events.emit("merge_main_already_red", mapOf(
                "count" to merged.size, "failedModules" to res.failedModules.toString(),
                "warn" to "main was already red over module(s) no PR in this batch touched — NOT bouncing these PRs; re-queuing until main is repaired",
            ))
            merged.forEach { requeueForRetry(it, "main-already-red") }
            return
        }
        if (merged.size == 1) {
            mBouncedSemantic.incrementAndGet()
            journal?.done(merged.first().branch)
            setPr(merged.first().branch, "bounced", true, reason = "gate-red")
            events.emit("merge_bounce_culprit", mapOf("branch" to merged.first().branch, "module" to merged.first().module))
            return
        }
        // Attribute by the PR's ACTUAL diff (ground truth), not its declared module — a mis-declaring agent
        // would otherwise get an innocent PR (that happens to own a failed module) bounced in its place. With a
        // module graph a PR is a culprit iff one of ITS diff modules is red. Attribution is only trustworthy if
        // every PR maps to a known module set — a PR whose diff touches a root/shared path (affectedModules ==
        // null) could break anything, so fall back to bisection. Without a graph, attribute by declared module.
        val attributable: Boolean
        val culprits: List<PullRequest>
        if (prMods.isNotEmpty()) {
            attributable = merged.all { prMods[it] != null }
            culprits = if (attributable) merged.filter { (prMods[it] ?: emptySet()).any { m -> m in res.failedModules } } else emptyList()
        } else {
            attributable = merged.none { it.module == null }
            culprits = if (attributable) merged.filter { it.module in res.failedModules } else emptyList()
        }
        if (culprits.isNotEmpty() && culprits.size < merged.size) {
            // attribution pinpointed the bad PRs -> bounce them, re-test the remainder soundly (one pass)
            culprits.forEach {
                mBouncedSemantic.incrementAndGet()
                journal?.done(it.branch)
                setPr(it.branch, "bounced", true, reason = "gate-red-culprit")
                events.emit("merge_bounce_culprit", mapOf("branch" to it.branch, "module" to it.module))
            }
            processBatched(merged.filterNot { it in culprits }, inheritedFailScope = failScope)
        } else {
            // Unattributable red batch (e.g. non-gradle / null-module: no module graph to blame). The old
            // fallback re-gated every PR INDIVIDUALLY — O(n) slow gates, which collapses throughput on real
            // builds the moment any batch contains a bad PR. BISECT instead: gate each half independently
            // (each processBatched re-merges from main, so it's self-contained + sound). A clean half lands in
            // ONE gate; a red half recurses. Cost is O(k·log n) gates for k bad PRs, not O(n).
            val mid = merged.size / 2
            processBatched(merged.subList(0, mid).toList(), inheritedFailScope = failScope)
            processBatched(merged.subList(mid, merged.size).toList(), inheritedFailScope = failScope)
        }
    }

    /** Union of [scope] with [extra]; null ("full project") dominates — a full gate already covers everything. */
    private fun widenScope(scope: Set<String>?, extra: Set<String>?): Set<String>? = when {
        scope == null -> null
        extra.isNullOrEmpty() -> scope
        else -> scope + extra
    }

    /** The affected closures of [modules] plus the modules themselves (a failed module name from the gate may
     *  be outside the router's known set; include it directly so it's still in the widened re-test scope). */
    private fun closureScopeOf(modules: Collection<String>): Set<String> =
        modules.flatMap { runCatching { router.affectedClosure(it) }.getOrDefault(emptySet()) }.toSet() + modules

    /**
     * Compare-and-set land for a batch: set main = [head] ONLY if main is still [mainBase] — i.e. it hasn't
     * moved since this batch was merged + tested. The batched gate runs OFF the git lock (for concurrency),
     * so a concurrent async optimistic gate could `revertFromMain` (move main) meanwhile; an unconditional
     * force-set would CLOBBER that revert, resurrecting a just-reverted bad commit and landing a state that
     * was never tested against the new main. If main moved, the batch is stale -> re-queue (re-merge against
     * the new main, re-gate) instead of landing. Returns true iff it landed.
     */
    private fun landBatch(head: String, mainBase: String, merged: List<PullRequest>): Boolean = synchronized(gitLock) {
        if (GitOps.rev(bare, "main") != mainBase) {
            events.emit("merge_batch_stale", mapOf("count" to merged.size, "why" to "main-moved-during-gate"))
            merged.forEach { requeueForRetry(it, "main-moved") }
            false
        } else {
            GitOps.setBranch(bare, "main", head)
            true
        }
    }

    /** Merge [pr] onto the current [target] in the integration worktree. MUST be called under [gitLock]. */
    private fun mergeOnto(target: String, pr: PullRequest): Boolean {
        GitOps.git(integ, "checkout", "-q", "--detach", target)
        GitOps.git(integ, "reset", "-q", "--hard", target)
        val m = GitOps.git(integ, "merge", "--no-ff", "-m", "merge ${pr.branch}", pr.branch)
        if (m.ok) return true
        GitOps.git(integ, "merge", "--abort")
        mBouncedTextual.incrementAndGet(); recordConflictOutcome(bounced = true)
        journal?.done(pr.branch)
        setPr(pr.branch, "bounced", true, reason = "textual-conflict")
        events.emit("merge_bounce_textual", mapOf("branch" to pr.branch))
        return false
    }

    /** Union of affected closures of [prs]; null (=> full test) if any module is unknown. */
    private fun affectedClosureOf(prs: List<PullRequest>): Set<String>? {
        if (prs.any { it.module == null }) return null
        return prs.flatMap { router.affectedClosure(it.module!!) }.toSet()
    }

    /**
     * The gate scope for a batch. Prefers the ACTUAL diff (ground truth, more reliable than the agent's
     * declared module): map the changed paths to their modules and union their closures — the true blast
     * radius, narrower than full. Falls back to the declared-module closure (or full) when there's no module
     * graph, or when the diff touches a root/shared path ([ChangePolicy.affectedModules] returns null).
     */
    private fun batchScope(merged: List<PullRequest>, changed: List<String>): Set<String>? {
        if (moduleDirs.isNotEmpty()) {
            ChangePolicy.affectedModules(changed, moduleDirs)?.let { mods ->
                return mods.flatMap { router.affectedClosure(it) }.toSet()
            }
        }
        return affectedClosureOf(merged)
    }

    private fun recordTtl(pr: PullRequest) {
        if (pr.recovered) return // its submittedAtNanos is from a prior process -> not a real submit-to-land sample
        synchronized(ttlNanos) {
            ttlNanos.addLast(System.nanoTime() - pr.submittedAtNanos)
            while (ttlNanos.size > MAX_TTL_SAMPLES) ttlNanos.removeFirst() // keep only recent (bounded memory)
        }
    }

    // ---- lifecycle ----
    fun start() {
        if (running) return
        running = true
        loop = thread(name = "merge-orchestrator", isDaemon = true) {
            while (running) {
                try {
                    if (drainAndProcess() == 0) Thread.sleep(20)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    // A single PR's processing error must never kill the queue loop (it would silently
                    // wedge all future merges). Log and continue; the offending PR is dropped this pass.
                    mErrors.incrementAndGet()
                    events.emit("merge_loop_error", mapOf("error" to (e.message ?: e.toString())))
                }
            }
        }
    }

    /** Wait until the queue is drained AND all async gates have settled (or [timeoutMs] elapses). */
    fun awaitQuiescent(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (queue.isEmpty() && processing.get() == 0 && inFlightGates.get() == 0) return true
            Thread.sleep(10)
        }
        return false
    }

    fun shutdown() {
        shutDown = true // reject any further submits immediately (before the loop fully stops)
        running = false
        loop?.join(2000)
        // Drain in-flight gates BEFORE returning: a gate build still running when the executor shuts down could
        // create a warm server AFTER the executor cleared its map (leaked, then reaped mid-build). awaitQuiescent
        // waits for the queue + async gates to settle; then stop the pool, escalating past a hard deadline.
        awaitQuiescent(GATE_DRAIN_MS)
        gatePool.shutdown()
        if (!gatePool.awaitTermination(GATE_DRAIN_MS, TimeUnit.MILLISECONDS)) {
            gatePool.shutdownNow()
            events.emit("merge_shutdown_gate_timeout", emptyMap())
        }
    }

    fun snapshot(): Map<String, Any> {
        val ttls = synchronized(ttlNanos) { ttlNanos.toList() }.sorted()
        // p50 alone hides the tail. p95/p99 are what surface queue-starvation under load. Same bounded sample
        // buffer, so this is free.
        fun pct(p: Int) = if (ttls.isEmpty()) 0 else ttls[((ttls.size * p) / 100).coerceIn(0, ttls.size - 1)] / 1_000_000
        val p50Ms = pct(50)
        return mapOf(
            "submitted" to mSubmitted.get(),
            "landed" to mLanded.get(),
            "optLanded" to mOptLanded.get(),
            "batchLanded" to mBatchLanded.get(),
            "bouncedTextual" to mBouncedTextual.get(),
            "bouncedSemantic" to mBouncedSemantic.get(),
            "branchMissing" to mBranchMissing.get(), // submitted branches the hub never received (distinct signal)
            "reverts" to mReverts.get(),
            "optMainAlreadyRed" to mOptMainAlreadyRed.get(), // H13 guard fires (innocents re-queued, not blamed)
            "integTests" to mIntegTests.get(),
            "inFlightGates" to inFlightGates.get(),
            // queueDepth + processing expose the BACKLOG: inFlightGates only covers the optimistic async path,
            // so a batched-only workload shows inFlightGates=0 while a deep queue is still draining. Operators
            // (and benchmarks) need this to know the system is busy, not idle. (Surfaced via /metrics too.)
            "queueDepth" to queueDepth.get(),
            "processing" to processing.get(),
            "gatesSkipped" to mGatesSkipped.get(), // change-aware skips (inert/doc-only changes never tested)
            "errors" to mErrors.get(),
            "p50TimeToLandMs" to p50Ms,
            "p95TimeToLandMs" to pct(95),
            "p99TimeToLandMs" to pct(99),
            // H7' observability: the live conflict signal + the route it currently implies. Lets a load harness sample
            // a ROUTING TIMELINE (does auto track a shifting/chaotic workload?) and operators see why it routes as it does.
            "recentBounceRate" to recentBounceRate.get(),
            "conflictRoute" to when {
                // PURE reads for the new routers (do NOT call effectiveThreshold here — banditThreshold() mutates state;
                // a metrics scrape must never roll the bandit window).
                banditRouting -> if (banditRoute.get() == 1) "optimistic-bandit" else "batch-bandit"
                // sticky regime (gateFillState is maintained on every routing decision; with hysteresis off it equals
                // the instantaneous ">= thr" crossing). PURE read — never mutates.
                gateFillRouting -> if (gateFillState.get()) "optimistic-gatefill" else "batch-gatefill"
                !conflictAdaptive -> "static"
                effectiveThreshold() > 0 -> "optimistic"
                else -> "batch"
            },
            // H17 bandit observability (0 when off): lets the A/B PROVE which arm it converged to + how many windows ran.
            "banditRoute" to (if (banditRoute.get() == 1) "opt" else "batch"),
            "banditRewardOpt" to banditRewardOpt.get(),
            "banditRewardBatch" to banditRewardBatch.get(),
            "banditWindows" to banditWindowCount.get(),
        )
    }

    internal companion object {
        const val MAX_TTL_SAMPLES = 2048 // recent time-to-land window for the p50 metric (bounds memory)
        const val MAX_PR_OUTCOMES = 4096 // bounded LRU of per-branch lifecycle outcomes for GET /merge/pr
        const val CONFLICT_MIN_SAMPLES = 20    // H7: warm up the bounce-rate estimate before adapting (batch until then)
        const val HIGH_CONFLICT_RATE = 0.35    // H7: textual-bounce fraction at/above which optimism beats batch
        const val CONFLICT_EWMA_ALPHA = 0.05   // H7': EWMA weight per outcome (~20-outcome window) — tracks the CURRENT
                                               // conflict regime so the flip isn't dragged by lifetime warmup history
        const val DISJOINT_OVERFETCH = 4       // H8: candidate pool = batchCap*this; bounds drain latency + fairness

        /** H8 pure composition: from [candidates] in FIFO order, greedily select PRs whose changed-file sets are
         *  pairwise DISJOINT (up to [cap]); return (selected, deferred). A PR is DEFERRED only on PROVEN overlap —
         *  its file set shares a path with one already claimed by a selected PR. An EMPTY file set (change-awareness
         *  off, or undiscoverable diff) carries no overlap evidence, so it is treated as disjoint and SELECTED
         *  (never worse than FIFO). Stable/order-preserving: selected and deferred each keep input order, so the
         *  FIFO head always wins its seat (no starvation — a deferred PR is re-tried next round, by then its blocker
         *  has landed). Pure + unit-testable; [filesOf] is the only impurity, injected. */
        fun composeDisjointBatch(
            candidates: List<PullRequest>,
            cap: Int,
            filesOf: (PullRequest) -> Set<String>,
        ): Pair<List<PullRequest>, List<PullRequest>> {
            val selected = ArrayList<PullRequest>(minOf(cap, candidates.size))
            val deferred = ArrayList<PullRequest>()
            val claimed = HashSet<String>()
            for (pr in candidates) {
                if (selected.size >= cap) { deferred.add(pr); continue }
                val files = filesOf(pr)
                if (files.isNotEmpty() && files.any { it in claimed }) deferred.add(pr) // proven overlap -> defer
                else { selected.add(pr); claimed.addAll(files) }
            }
            return selected to deferred
        }

        /** H7 pure decision: closure-size threshold given lifetime (landed, textual-bounced) counts. Warm up (<min
         *  samples) -> 0 (batch). Then BATCH (0) while the bounce fraction is below HIGH_CONFLICT_RATE (low conflict ->
         *  amortize clean PRs), OPTIMISTIC ([optimisticThr]) at/above it (high conflict -> independent lands dodge
         *  batch cascade-bounces). Pure + unit-testable; the orchestrator feeds it live cumulative counters. */
        fun conflictThreshold(landed: Long, bounced: Long, optimisticThr: Int): Int {
            val total = landed + bounced
            if (total < CONFLICT_MIN_SAMPLES) return 0
            return if (bounced.toDouble() / total >= HIGH_CONFLICT_RATE) optimisticThr else 0
        }

        /** H7' pure decision: same rule, but on the RECENT (EWMA) bounce rate instead of the lifetime ratio.
         *  [recentRate] is the orchestrator's EWMA of recent merge outcomes (1=textual bounce, 0=land); [totalSamples]
         *  is the lifetime outcome count, used only for the warmup gate. The lifetime ratio LAGS (the
         *  bounce rate builds over a run, so the cumulative average never crosses the threshold in time); the windowed
         *  rate tracks the current regime and flips promptly. Pure + unit-testable. */
        fun conflictThresholdWindowed(recentRate: Double, totalSamples: Long, optimisticThr: Int,
                                      highConflictRate: Double = HIGH_CONFLICT_RATE): Int {
            if (totalSamples < CONFLICT_MIN_SAMPLES) return 0
            return if (recentRate >= highConflictRate) optimisticThr else 0
        }
        const val MAX_GATE_RETRIES = 5   // give up re-queuing a PR after this many inconclusive/fault gates
        const val GATE_DRAIN_MS = 30_000L // shutdown: how long to let in-flight gates settle before forcing the pool down
    }
}
