package dev.arxael.merge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The thin git-CLI wrapper the orchestrator rides on. A wrong exit-code/output mapping here would make the
 * orchestrator misread merges/reverts, so the basic contract (ok vs not-ok, captured output, rev, setBranch)
 * is pinned against a real repo.
 */
class GitOpsTest {
    private val tmp: Path = Files.createTempDirectory("arxael-gitops-test")

    @AfterTest
    fun cleanup() = Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) }.let {} }

    private fun g(vararg a: String) = GitOps.git(tmp, "-c", "user.name=t", "-c", "user.email=t@t", *a)

    @Test
    fun `ok is true on success and false on a failing command`() {
        assertTrue(GitOps.git(tmp, "-c", "init.defaultBranch=main", "init", "-q").ok)
        assertTrue(GitOps.ok(tmp, "status"))
        assertFalse(GitOps.ok(tmp, "checkout", "no-such-branch"), "a failing git command -> ok=false")
    }

    @Test
    fun `captures output, resolves rev, and setBranch moves a branch`() {
        GitOps.git(tmp, "-c", "init.defaultBranch=main", "init", "-q")
        Files.writeString(tmp.resolve("f.txt"), "a")
        g("add", "-A"); g("commit", "-q", "-m", "c1")
        val c1 = GitOps.rev(tmp, "HEAD")
        assertEquals(40, c1.length, "rev resolves a full sha")
        Files.writeString(tmp.resolve("f.txt"), "b")
        g("add", "-A"); g("commit", "-q", "-m", "c2")
        val c2 = GitOps.rev(tmp, "HEAD")
        assertTrue(c1 != c2)
        // setBranch points a branch at an older commit (the orchestrator's "land"/"revert" primitive)
        assertTrue(GitOps.setBranch(tmp, "rollback", c1))
        assertEquals(c1, GitOps.rev(tmp, "rollback"))
    }

    @Test
    fun `rev of an unknown ref is blank, not an exception`() {
        GitOps.git(tmp, "-c", "init.defaultBranch=main", "init", "-q")
        assertEquals("", GitOps.rev(tmp, "definitely-not-a-ref"))
    }

    @Test
    fun `setBranch returns false when the ref update is refused (checked-out branch), never throws`() {
        // `git branch -f <currentBranch>` is REFUSED when the branch is checked out (exit != 0) — the exact
        // production trigger behind the 2026-06-16 phantom-land (main checked out in a stray worktree). The
        // land/revert primitive MUST report that as false (not throw, not a phantom success), so the orchestrator
        // guards that discard-the-Boolean bug can detect a publish that did not happen.
        GitOps.git(tmp, "-c", "init.defaultBranch=main", "init", "-q")
        Files.writeString(tmp.resolve("f.txt"), "a")
        g("add", "-A"); g("commit", "-q", "-m", "c1")
        val c1 = GitOps.rev(tmp, "HEAD")
        Files.writeString(tmp.resolve("f.txt"), "b")
        g("add", "-A"); g("commit", "-q", "-m", "c2")
        val c2 = GitOps.rev(tmp, "HEAD")
        assertFalse(GitOps.setBranch(tmp, "main", c1), "a refused force-update maps to false, not an exception")
        assertEquals(c2, GitOps.rev(tmp, "main"), "main must be unchanged after a refused update")
    }
}
