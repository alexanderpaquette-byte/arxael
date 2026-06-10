package dev.arxael.merge

import java.nio.file.Path

/**
 * A PR awaiting integration: a branch in the bare repo, the module it touches (Gradle project path like
 * ":app:core", or null if unknown -> treated as full-scope), and when it was submitted (for time-to-land).
 *
 * The orchestrator assumes a PR arrives already branch-tested green (the agent's responsibility, and the
 * phase-5 finding: branch-gating is what makes the whole design sound). The orchestrator's job is the
 * INTEGRATION check — does this change still pass once merged onto the current main, alongside everyone else.
 */
data class PullRequest(
    val branch: String,
    val module: String? = null,
    val submittedAtNanos: Long = System.nanoTime(),
    val agentId: String? = null,
    /** How many times this PR has been re-queued after an INCONCLUSIVE gate (infra OVERLOADED/ERROR). Bounded
     *  so a persistently-broken substrate can't make a PR retry — or flap main — forever. */
    val gateAttempts: Int = 0,
    /** True for a PR reconstructed from the journal on crash-recovery: its [submittedAtNanos] is from a PRIOR
     *  process (nanoTime has no epoch across restarts), so its time-to-land is meaningless and excluded from p50. */
    val recovered: Boolean = false,
)

/**
 * The current lifecycle state of a submitted PR, queryable via `GET /merge/pr?branch=` so an agent can ask
 * "did MY branch land?" directly instead of racing the aggregate `landed` counter (which, with N concurrent
 * agents, can't tell whose PR moved it). [terminal] = the PR has reached a final state and won't change.
 *
 * States: `queued` (waiting) · `gating` (optimistically landed, async verify in flight; or batched-in-gate) ·
 * `landed` (on main, verified/batched-landed — terminal) · `reverted` (was landed, gate red, reverted off —
 * terminal) · `bounced` (never landed: textual conflict or attributed culprit — terminal) · `missing` (branch
 * not in the hub repo — terminal) · `error` (gate gave up / revert conflicted — terminal). `unknown` is
 * returned by the API for a branch the orchestrator has no record of (evicted or never submitted).
 */
data class PrOutcome(val state: String, val terminal: Boolean, val commit: String? = null, val reason: String? = null)

/**
 * Outcome of testing a merge candidate. [failedModules] feeds culprit attribution on a red batch.
 * [conclusive] = the gate actually ran the tests and got a verdict. When it's false (the substrate was
 * OVERLOADED, or an infra ERROR), `green` is meaningless: the orchestrator must NOT bounce/revert a PR as if
 * it failed — it should retry — otherwise an infra blip silently rejects good PRs.
 */
data class GateResult(
    val green: Boolean,
    val failedModules: Set<String> = emptySet(),
    val conclusive: Boolean = true,
)

/**
 * The orchestrator's one pluggable dependency: run the tests for a worktree state.
 *
 *  - [modules] == null  -> full integration test (batched gate-then-land).
 *  - [modules] non-null -> scope to those modules' tests (the optimistic module-scoped async gate, and the
 *    incremental batched gate) — the proven 7× lever: only re-test what the merge could have broken.
 *
 * Production impl ([ExecutorMergeGate]) routes through the warm executor at "high" priority so landings
 * never starve behind agent branch-tests; tests inject a deterministic fake.
 */
fun interface MergeGate {
    fun test(worktree: Path, modules: Set<String>?, label: String): GateResult
}
