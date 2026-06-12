package dev.arxael.protocol

import kotlinx.serialization.Serializable

/**
 * The wire contract for the single `/invoke` surface every caller routes through.
 *
 * Deliberately small and flat: a fleet of agents speaks exactly this, nothing else.
 */
@Serializable
data class InvokeSpec(
    /** Adapter name, e.g. "gradle" or "noop". */
    val adapter: String,
    /** Project directory / git worktree the build runs in. */
    val worktree: String,
    /** Build targets (gradle tasks). */
    val tasks: List<String> = emptyList(),
    /** Extra build args — each is arg-allowlist checked, fail-closed. */
    val args: List<String> = emptyList(),
    /** Optional caller id, for accounting in the event log. */
    val agentId: String? = null,
    /** Scheduling lane: "high" reserves capacity (merge-gate tests that protect main / land fast) so
     *  bulk "normal" work (agent branch-tests) can't starve them under contention. Default "normal". */
    val priority: String = "normal",
)

/** Terminal status of an invocation. */
enum class InvokeStatus { SUCCESS, FAILED, REJECTED, ERROR, OVERLOADED }

@Serializable
data class InvokeOutcome(
    val ok: Boolean,
    val status: String,
    /** Which warm worktree-server handled it (or "-" if rejected before dispatch). */
    val server: String,
    /** Time the request waited for a concurrency permit. */
    val queueMs: Long,
    /** Time the build itself ran. */
    val runMs: Long,
    /** Tail of build output (bounded). */
    val output: String,
    val message: String? = null,
)

// ---- merge orchestrator surface (one project; agents submit branch-tested PRs to land on shared main) ----

/**
 * Register the project whose `main` the orchestrator lands onto. [forwardDeps] maps a Gradle module path
 * to the modules it depends ON (":app" -> [":core"]); the orchestrator inverts it to size each change's
 * affected closure and auto-route. Omit/empty => every PR is treated as full-scope (always-sound batched
 * gate). The agent-runner creates the bare repo + `main` before registering.
 */
@Serializable
data class MergeRegisterSpec(
    val repo: String,
    val forwardDeps: Map<String, List<String>> = emptyMap(),
    val threshold: Int = -1,   // <0 = use the daemon's ARXAEL_MERGE_MODE-derived default (config.routeThreshold)
    val gateWorktrees: Int = 4,
)

/** Submit a branch-tested PR. [module] is its Gradle module path (null => full-scope, routed batched). */
@Serializable
data class MergeSubmitSpec(
    val branch: String,
    val module: String? = null,
    val agentId: String? = null,
)
