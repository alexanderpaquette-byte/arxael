package dev.arxael.merge

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Thin, synchronous wrapper over the `git` CLI — the merge orchestrator's only IO dependency.
 *
 * Deliberately the git CLI (not JGit): the substrate already shells to the build tool, the CLI is the
 * battle-tested merge/revert engine, and shelling keeps `core/` dependency-light (lean, agnostic).
 * Every call is explicit about its working tree so the orchestrator can drive a bare
 * repo, an integration worktree, and a pool of gate worktrees without ambient state.
 *
 * All mutating sequences in the orchestrator hold a single lock (git's index/refs are not safe under
 * concurrent writes in one worktree); reads (rev-parse) are cheap and serialized the same way.
 */
object GitOps {
    data class Result(val code: Int, val out: String) {
        val ok: Boolean get() = code == 0
    }

    /** Per-invocation git config so the daemon is SELF-CONTAINED — never depends on ambient git state. A
     *  fresh box / container has no global `user.name`/`user.email`, so the orchestrator's own merge/revert
     *  COMMITS would fail ("tell me who you are") and nothing would land — a portability bug found by
     *  bench/install_container.sh. We also disable commit signing (no GPG prompt on locked-down boxes) and
     *  trust any worktree owner (container UID mismatch -> git's "dubious ownership" refusal). Passed as `-c`
     *  flags before the subcommand, harmless for read-only ops. */
    private val SELF_CONTAINED = listOf(
        "-c", "user.name=arxael", "-c", "user.email=arxael@localhost",
        "-c", "commit.gpgsign=false", "-c", "safe.directory=*",
    )

    /** Run a git command in [cwd]; stdout+stderr merged, trimmed. Never throws on non-zero exit. */
    fun git(cwd: Path, vararg args: String): Result {
        val cmd = buildList { add("git"); addAll(SELF_CONTAINED); addAll(args) }
        return try {
            val p = ProcessBuilder(cmd)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return Result(124, "git timed out: ${cmd.joinToString(" ")}")
            }
            Result(p.exitValue(), out.trim())
        } catch (e: Exception) {
            Result(127, "git failed to start: ${e.message}")
        }
    }

    fun ok(cwd: Path, vararg args: String): Boolean = git(cwd, *args).ok

    /** Resolve a ref to its commit sha, or "" if it can't be resolved (so callers can detect failure
     *  instead of getting git's error text back as if it were a sha). */
    fun rev(cwd: Path, ref: String): String {
        val r = git(cwd, "rev-parse", ref)
        return if (r.ok) r.out.trim() else ""
    }

    /** Force a branch in the bare repo to point at [commit] — the atomic "land" operation. */
    fun setBranch(bare: Path, branch: String, commit: String): Boolean =
        ok(bare, "branch", "-f", branch, commit)

    /** True iff [branch] exists as a local branch ref in [repo]. The orchestrator merges PR branches by name,
     *  so a branch the hub never received (the agent worked in a separate checkout and never pushed / never
     *  branched off the hub) would otherwise fail the merge and be mislabelled a "textual conflict". This lets
     *  the orchestrator give a distinct, actionable signal instead. */
    fun branchExists(repo: Path, branch: String): Boolean =
        ok(repo, "rev-parse", "--verify", "--quiet", "refs/heads/$branch")

    /**
     * The repo-relative paths a [range] changed (e.g. "main...feature" = the branch's own changes since it
     * diverged from main). Ref-based, so it works in a BARE repo (no worktree). Empty on any error — the
     * caller treats "can't tell what changed" as full-scope (safe), never as "nothing changed".
     */
    fun changedFiles(repo: Path, range: String): List<String> {
        val r = git(repo, "diff", "--name-only", range)
        return if (r.ok) r.out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList() else emptyList()
    }
}
