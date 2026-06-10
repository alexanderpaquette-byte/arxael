package dev.arxael.merge

/**
 * Fail-closed validation for /merge/submit fields — the merge surface's counterpart to [dev.arxael.invoke.ArgAllowlist]
 * on /invoke (which the merge path does NOT run). branch/module/agentId flow two unsafe ways:
 *
 *   1. into git as POSITIONAL arguments (`git merge --no-ff … <branch>`, `git diff main...<branch>`): a value
 *      starting with `-` is parsed as a git OPTION, not a ref — argument injection that corrupts the merge.
 *   2. into the TAB-separated PR journal (`SUBMIT\t<branch>\t<module>\t<agentId>`): an embedded tab or newline
 *      splits/forges a record, desyncing crash-recovery's [PrJournal.pending] parse.
 *
 * Allowlist, not denylist: only the characters a real git branch ref / Gradle module path / agent id need.
 */
object MergeArgPolicy {
    sealed interface Result
    data object Ok : Result
    data class Rejected(val reason: String) : Result

    // git refnames are permissive, but these cover every ordinary branch; we deliberately exclude shell/git
    // metacharacters, whitespace, tab, and newline. Capped length so one caller can't bloat the journal line.
    private val BRANCH = Regex("^[A-Za-z0-9._/+-]{1,255}$")
    private val MODULE = Regex("^[A-Za-z0-9._:/+-]{1,255}$") // Gradle module paths use ':' (e.g. :app:core)
    private val AGENT = Regex("^[A-Za-z0-9._:@/+-]{1,255}$")

    fun check(branch: String, module: String?, agentId: String?): Result {
        if (branch.isBlank()) return Rejected("branch is required")
        if (branch.startsWith("-")) return Rejected("branch may not start with '-' (git would read it as an option)")
        if (!BRANCH.matches(branch)) return Rejected("branch has illegal characters (allowed: letters, digits, . _ / + -)")
        if (module != null) {
            if (module.startsWith("-")) return Rejected("module may not start with '-'")
            if (!MODULE.matches(module)) return Rejected("module has illegal characters (allowed: letters, digits, . _ : / + -)")
        }
        if (agentId != null && agentId.isNotEmpty() && !AGENT.matches(agentId)) {
            return Rejected("agentId has illegal characters")
        }
        return Ok
    }
}
