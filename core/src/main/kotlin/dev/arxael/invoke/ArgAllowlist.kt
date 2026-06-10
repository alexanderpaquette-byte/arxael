package dev.arxael.invoke

import dev.arxael.protocol.InvokeSpec
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fail-closed validation of an [InvokeSpec] before it reaches the executor.
 *
 * A single arg-allowlisted /invoke surface. "Allowlist", not denylist, and
 * "fail-closed" (unknown/uncertain input blocks by default). The point isn't shell-injection
 * (the Tooling API takes argv directly, no shell) — it's that a caller must not be able to pass
 * flags that subvert the substrate's own isolation (e.g. --gradle-user-home, --project-cache-dir,
 * --init-script) or reach outside its worktree. Anything not explicitly recognised is rejected.
 */
object ArgAllowlist {
    // Bounded lengths: a multi-MB token is never a legitimate task/value/prop, and forwarding one wastes
    // memory/argv space. Caps are generous (real tasks/filters are short) but finite — fail closed on absurd input.
    private val ADAPTER = Regex("^[a-z][a-z0-9_-]{0,31}$")
    private val TASK = Regex("^[A-Za-z0-9_.:\\-]{1,256}$")
    private val VALUE = Regex("^[A-Za-z0-9_.:*$#/\\-]{1,512}$")
    private val PROP = Regex("^-P[A-Za-z0-9_.]{1,256}=[^\\s]{0,512}$")
    private val NOOP_KV = Regex("^[A-Za-z][A-Za-z0-9]*=[A-Za-z0-9_.\\-]*$") // e.g. sleepMs=50

    /** Safe standalone flags. Note: cache/parallelism/isolation flags are INJECTED, never accepted. */
    private val EXACT = setOf(
        "--offline", "--stacktrace", "--info", "--quiet", "-q",
        "--console=plain", "--rerun-tasks", "--continue", "--warning-mode=none",
    )

    /** Flags that consume the next token as a value. */
    private val VALUE_FLAGS = setOf("--tests", "-x", "--exclude-task")

    sealed interface Result
    object Ok : Result
    data class Rejected(val reason: String) : Result

    fun check(spec: InvokeSpec): Result {
        if (!ADAPTER.matches(spec.adapter)) return Rejected("bad adapter name '${spec.adapter}'")

        if (spec.worktree.isBlank()) return Rejected("worktree is required")
        val wt: Path = try {
            Path.of(spec.worktree).toAbsolutePath().normalize()
        } catch (e: Exception) {
            return Rejected("bad worktree path: ${e.message}")
        }
        if (!Files.isDirectory(wt)) return Rejected("worktree does not exist or is not a directory: $wt")

        for (t in spec.tasks) {
            if (!TASK.matches(t)) return Rejected("disallowed task token '$t'")
            // A flag-shaped "task" (the TASK regex allows '-') would bypass the args allowlist entirely and,
            // for gradle, could subvert the substrate's INJECTED isolation (--gradle-user-home / --init-script
            // / --project-cache-dir). Real gradle tasks are never flags, so forbid flag-shaped gradle tasks.
            // Non-gradle (command) adapters legitimately pass tool flags as tasks (e.g. pytest -q), and have
            // no injected isolation to subvert, so the guard is gradle-specific.
            if (spec.adapter == "gradle" && t.startsWith("-")) {
                return Rejected("gradle task must not be a flag (would bypass the arg allowlist): '$t'")
            }
        }

        var i = 0
        while (i < spec.args.size) {
            val a = spec.args[i]
            when {
                a in EXACT -> i++
                PROP.matches(a) -> i++
                NOOP_KV.matches(a) -> i++
                a in VALUE_FLAGS -> {
                    val v = spec.args.getOrNull(i + 1) ?: return Rejected("flag '$a' needs a value")
                    if (!VALUE.matches(v)) return Rejected("bad value '$v' for '$a'")
                    i += 2
                }
                else -> return Rejected("disallowed arg '$a'")
            }
        }
        return Ok
    }
}
