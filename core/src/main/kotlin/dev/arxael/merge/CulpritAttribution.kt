package dev.arxael.merge

/**
 * Pure parser: from a build tool's failure output, name the MODULES whose tasks failed.
 *
 * The proven "a red module names the culprit PR for free" lever: when a merge
 * gate fails, the failed module identifies the PR that touched it — so the orchestrator can land every
 * other PR and bounce only the culprit, from ONE incremental test, with no prefix-bisection re-queue churn.
 *
 * Generalized from the prototype's fixture-specific `:mod\d+:` regex to any Gradle project path
 * (`:app:core`, `:lib`, …). Like the prototype, it keys ONLY off failure-context lines (`Task :x FAILED`
 * and `Execution failed for task ':x'`) — NOT every task line — so an `--info` build doesn't flag every
 * module. Returns an empty set when nothing is attributable, which the caller MUST treat as "all suspect"
 * and fall back to the safe per-PR path (never silently land a red merge).
 */
object CulpritAttribution {
    // "> Task :app:core:test FAILED"
    private val TASK_FAILED = Regex("""Task (:[\w.:\-]+) FAILED""")
    // "Execution failed for task ':app:core:test'."
    private val EXEC_FAILED = Regex("""Execution failed for task '(:[\w.:\-]+)'""")

    /** Distinct module paths (e.g. ":mod3", ":app:core") whose tasks failed; empty if unattributable. */
    fun failedModules(output: String?): Set<String> {
        if (output.isNullOrEmpty()) return emptySet()
        val taskPaths = buildSet {
            TASK_FAILED.findAll(output).forEach { add(it.groupValues[1]) }
            EXEC_FAILED.findAll(output).forEach { add(it.groupValues[1]) }
        }
        // A task path is "<modulePath>:<taskName>"; the module is everything before the last ':'.
        // A root-project task (":test", idx == 0) can't be attributed to a subproject -> drop it
        // (caller treats the empty/partial result as all-suspect, the safe fallback).
        return taskPaths.mapNotNullTo(mutableSetOf()) { taskPath ->
            val idx = taskPath.lastIndexOf(':')
            if (idx <= 0) null else taskPath.substring(0, idx)
        }
    }
}
