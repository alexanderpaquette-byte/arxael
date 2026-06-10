package dev.arxael.merge

import dev.arxael.eventlog.EventLog
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The merge orchestrator — the productionized winner from the limit-finding campaign
 * modeled on `bench/merge_sim.py` into `core/`.
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
) {
    private val gitLock = Any()
    private val queue = ConcurrentLinkedQueue<PullRequest>()
    private val confirmations: MutableMap<String, Int> = confirmStore?.load() ?: HashMap()
    private val gatePool = Executors.newFixedThreadPool(maxOf(1, gateWorktrees.size)) { r ->
        Thread(r, "merge-gate").apply { isDaemon = true } // daemon: never block JVM exit if shutdown is missed
    }
    private val freeGateWts = ArrayBlockingQueue<Path>(maxOf(1, gateWorktrees.size)).apply { addAll(gateWorktrees) }
    private val inFlightGates = AtomicInteger(0)
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
    private val mGatesSkipped = AtomicInteger(0) // gates skipped by change-awareness (inert/doc-only changes)
    private val mBranchMissing = AtomicInteger(0) // PRs whose branch wasn't in the hub (distinct from textual)
    private val ttlNanos = ArrayDeque<Long>() // recent time-to-land samples (bounded; guarded by itself)

    // Per-PR lifecycle, keyed by branch, so an agent can query "did MY branch land?" (GET /merge/pr) instead of
    // racing the aggregate counter. Bounded LRU (access-order false = insertion order) so a long-lived daemon
    // can't grow it without bound; the data already exists in events.jsonl, this just makes it queryable.
    private val prOutcomes = object : LinkedHashMap<String, PrOutcome>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PrOutcome>): Boolean = size > MAX_PR_OUTCOMES
    }

    private fun setPr(branch: String, state: String, terminal: Boolean, commit: String? = null, reason: String? = null) {
        synchronized(prOutcomes) {
            prOutcomes[branch] = PrOutcome(state, terminal, commit, reason)
            (prOutcomes as Object).notifyAll() // wake any long-pollers in prStatusWait (instead of a busy spin)
        }
    }

    /** The last-known lifecycle state of [branch], or null if the orchestrator has no record of it. */
    fun prStatus(branch: String): PrOutcome? = synchronized(prOutcomes) { prOutcomes[branch] }

    /** Block until [branch] reaches a TERMINAL state or [timeoutMs] elapses (long-poll for the agent's land
     *  loop). Returns the last-known outcome (possibly null/non-terminal on timeout). Uses the prOutcomes
     *  monitor's wait/notify — a state change wakes waiters, so there's no busy spin (the prior 25ms-poll loop
     *  burned CPU at ~40Hz per waiter). Callers bound how many threads block here (see MergeService). */
    fun prStatusWait(branch: String, timeoutMs: Long): PrOutcome? {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(prOutcomes) {
            while (true) {
                val o = prOutcomes[branch]
                if (o != null && o.terminal) return o
                val remMs = (deadlineNs - System.nanoTime()) / 1_000_000
                if (remMs <= 0) return o // timed out -> return whatever we have (null or non-terminal)
                (prOutcomes as Object).wait(remMs)
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
        queue.add(pr)
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
        pending.forEach { mSubmitted.incrementAndGet(); queue.add(it); setPr(it.branch, "queued", false, reason = "recovered") }
    }

    /** Pull up to [batchCap] queued PRs and process them; returns how many were pulled (0 if idle). */
    fun drainAndProcess(): Int {
        val prs = ArrayList<PullRequest>(batchCap)
        while (prs.size < batchCap) { prs.add(queue.poll() ?: break) }
        if (prs.isEmpty()) return 0
        // A pulled-but-not-yet-landed PR is in neither the queue nor inFlightGates; count it as "processing"
        // so awaitQuiescent doesn't declare the system idle mid-merge (the window before a land/gate).
        processing.incrementAndGet()
        try { processBatch(prs) } finally { processing.decrementAndGet() }
        return prs.size
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
        val optimistic = resolved.filter { it.module != null && router.route(it.module) == MergeRoute.OPTIMISTIC }
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
        val landed = synchronized(gitLock) {
            if (!mergeOnto("main", pr)) return            // textual conflict (counted in mergeOnto)
            val head = GitOps.rev(integ, "HEAD")
            GitOps.setBranch(bare, "main", head)          // LAND immediately, atomically with the merge
            head
        }
        mLanded.incrementAndGet(); mOptLanded.incrementAndGet(); recordTtl(pr)
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
        gatePool.submit { asyncGate(pr, landed) }
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
            val conclusive = runCatching { gate.test(wt, scope, "opt-gate-${pr.branch}") }
            val res = conclusive.getOrNull()
            if (res != null && res.green) {
                if (scope == null) recordConfirmation(pr) // a full gate passed -> this module is one step more trusted
                journal?.done(pr.branch)
                setPr(pr.branch, "landed", true, commit = commit)
                events.emit("merge_gate_green", mapOf("branch" to pr.branch, "module" to pr.module))
                return
            }
            if (res != null && res.conclusive) {
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
                gatePool.submit { asyncGate(pr.copy(gateAttempts = pr.gateAttempts + 1), commit) }
                events.emit("merge_retry", mapOf("branch" to pr.branch, "attempt" to pr.gateAttempts + 1, "why" to "gate-inconclusive"))
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
            queue.add(pr.copy(gateAttempts = pr.gateAttempts + 1))
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
            mReverts.incrementAndGet(); mLanded.decrementAndGet()
            events.emit("merge_revert", mapOf("branch" to pr.branch, "module" to pr.module, "commit" to commit))
            true
        } else {
            mErrors.incrementAndGet()
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
        var mainBase = ""                               // the main commit this batch is merged onto + tested against
        val head = synchronized(gitLock) {
            GitOps.git(integ, "checkout", "-q", "--detach", "main")
            GitOps.git(integ, "reset", "-q", "--hard", "main")
            mainBase = GitOps.rev(integ, "HEAD")
            for (pr in prs) {
                val m = GitOps.git(integ, "merge", "--no-ff", "-m", "merge ${pr.branch}", pr.branch)
                if (m.ok) merged.add(pr) else {
                    GitOps.git(integ, "merge", "--abort"); mBouncedTextual.incrementAndGet(); journal?.done(pr.branch)
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
                mLanded.addAndGet(merged.size); mBatchLanded.addAndGet(merged.size); mGatesSkipped.incrementAndGet()
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
                mLanded.addAndGet(merged.size); mBatchLanded.addAndGet(merged.size)
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
        mBouncedTextual.incrementAndGet()
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
        val p50Ms = if (ttls.isEmpty()) 0 else ttls[ttls.size / 2] / 1_000_000
        return mapOf(
            "submitted" to mSubmitted.get(),
            "landed" to mLanded.get(),
            "optLanded" to mOptLanded.get(),
            "batchLanded" to mBatchLanded.get(),
            "bouncedTextual" to mBouncedTextual.get(),
            "bouncedSemantic" to mBouncedSemantic.get(),
            "branchMissing" to mBranchMissing.get(), // submitted branches the hub never received (distinct signal)
            "reverts" to mReverts.get(),
            "integTests" to mIntegTests.get(),
            "inFlightGates" to inFlightGates.get(),
            // queueDepth + processing expose the BACKLOG: inFlightGates only covers the optimistic async path,
            // so a batched-only workload shows inFlightGates=0 while a deep queue is still draining. Operators
            // (and benchmarks) need this to know the system is busy, not idle. (Surfaced via /metrics too.)
            "queueDepth" to queue.size,
            "processing" to processing.get(),
            "gatesSkipped" to mGatesSkipped.get(), // change-aware skips (inert/doc-only changes never tested)
            "errors" to mErrors.get(),
            "p50TimeToLandMs" to p50Ms,
        )
    }

    private companion object {
        const val MAX_TTL_SAMPLES = 2048 // recent time-to-land window for the p50 metric (bounds memory)
        const val MAX_PR_OUTCOMES = 4096 // bounded LRU of per-branch lifecycle outcomes for GET /merge/pr
        const val MAX_GATE_RETRIES = 5   // give up re-queuing a PR after this many inconclusive/fault gates
        const val GATE_DRAIN_MS = 30_000L // shutdown: how long to let in-flight gates settle before forcing the pool down
    }
}
