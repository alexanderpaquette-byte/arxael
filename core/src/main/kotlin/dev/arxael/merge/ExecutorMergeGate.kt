package dev.arxael.merge

import dev.arxael.executor.WarmExecutor
import dev.arxael.protocol.InvokeSpec
import dev.arxael.protocol.InvokeStatus
import java.nio.file.Path

/**
 * Production [MergeGate]: run the integration/gate tests on the warm executor — the same bounded substrate
 * the agents use, so a landing competes for the SAME capacity (the phase-2 coupling) but on the reserved
 * "high" lane so it can never starve behind a flood of agent branch-tests.
 *
 *  - module-scoped (incremental) when [modules] is given -> tasks = ":modX:test" per module (the 7× lever:
 *    re-test only what the merge could have broken). Full "test" otherwise.
 *  - a failed gate's output is run through [CulpritAttribution] so a red batch names its culprit PRs.
 *
 * Anything that isn't a clean SUCCESS (FAILED, ERROR, OVERLOADED) is treated as NOT green — fail closed:
 * the orchestrator must never land on an inconclusive gate. (OVERLOADED is rare on the reserved lane; if it
 * happens the PR simply isn't landed this pass and is retried.)
 */
class ExecutorMergeGate(
    private val executor: WarmExecutor,
    private val adapter: String = "gradle",
) : MergeGate {
    override fun test(worktree: Path, modules: Set<String>?, label: String): GateResult {
        val outcome = executor.submit(
            InvokeSpec(
                adapter = adapter,
                worktree = worktree.toString(),
                tasks = gateTasks(adapter, modules),
                args = gateArgs(adapter),
                agentId = "merge-gate",
                priority = "high",
            ),
        )
        return toGateResult(outcome.status, outcome.output)
    }

    companion object {
        /**
         * The tasks the gate runs, per adapter (pure -> unit-testable). Gradle supports incremental
         * module-scoped gating (`:modX:test`, the 7x lever) and a `test` lifecycle task. Non-JVM adapters
         * (pytest/go/cargo/npm via [CommandAdapter][dev.arxael.adapter.CommandAdapter]) have no module-scoped
         * task graph and a default full-suite command, so the gate runs the WHOLE suite (empty tasks ->
         * adapter default). Those PRs route BATCHED (no module graph), and a red gate with no parseable
         * culprit safely treats the whole batch as suspect — sound, just less granular than gradle.
         */
        fun gateTasks(adapter: String, modules: Set<String>?): List<String> = when {
            adapter != "gradle" -> emptyList()                 // run the adapter's default full-suite command
            modules.isNullOrEmpty() -> listOf("test")          // gradle: full lifecycle test
            else -> modules.map { "$it:test" }                 // gradle: incremental, module-scoped
        }

        /** Gate-only Gradle args. `--continue` makes a multi-module gate run EVERY module's tests even after the
         *  first fails, so [CulpritAttribution] sees all failed modules in ONE pass (else attribution under-reports
         *  and a second bad PR slips into the re-tested remainder). No-op for non-gradle adapters. */
        fun gateArgs(adapter: String): List<String> =
            if (adapter == "gradle") listOf("--continue") else emptyList()

        /**
         * Pure mapping from an executor outcome to a gate verdict (extracted so it's unit-testable without a
         * live executor). SUCCESS -> green. A genuine test FAILURE is conclusive and carries attributable
         * culprits. OVERLOADED/ERROR/REJECTED are INCONCLUSIVE — the tests didn't reach a verdict, so the
         * orchestrator must retry, not reject (else an infra blip silently bounces good PRs).
         */
        fun toGateResult(status: String, output: String): GateResult = when (status) {
            InvokeStatus.SUCCESS.name -> GateResult(green = true)
            InvokeStatus.FAILED.name ->
                GateResult(green = false, failedModules = CulpritAttribution.failedModules(output), conclusive = true)
            else -> GateResult(green = false, conclusive = false)
        }
    }
}
