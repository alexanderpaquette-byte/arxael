package dev.arxael.merge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The /merge/submit fail-closed validator. branch/module/agentId flow into git as positional args (a leading
 * '-' becomes a git option) and into the tab-separated PR journal (a tab/newline forges a record). These must
 * be rejected at the boundary, while ordinary refs/module paths pass.
 */
class MergeArgPolicyTest {
    private fun ok(r: MergeArgPolicy.Result) = r == MergeArgPolicy.Ok
    private fun rejected(r: MergeArgPolicy.Result) = r is MergeArgPolicy.Rejected

    @Test fun `ordinary branch + gradle module + agent pass`() {
        assertTrue(ok(MergeArgPolicy.check("feature/login-v2", ":app:core", "agent-7")))
        assertTrue(ok(MergeArgPolicy.check("fix_123", null, null)))
        assertTrue(ok(MergeArgPolicy.check("release-1.2.3", "module.sub", "bot@host")))
    }

    @Test fun `a leading-dash branch is rejected (would be parsed as a git option)`() {
        assertTrue(rejected(MergeArgPolicy.check("--upload-pack=x", null, null)))
        assertTrue(rejected(MergeArgPolicy.check("-X", null, null)))
    }

    @Test fun `a tab or newline in any field is rejected (journal record forgery)`() {
        assertTrue(rejected(MergeArgPolicy.check("feat\tDONE\tother", null, null)))
        assertTrue(rejected(MergeArgPolicy.check("feat\nDONE\tother", null, null)))
        assertTrue(rejected(MergeArgPolicy.check("ok", "mod\tx", null)))
        assertTrue(rejected(MergeArgPolicy.check("ok", null, "a\nb")))
    }

    @Test fun `empty branch and whitespace and shell metacharacters are rejected`() {
        assertTrue(rejected(MergeArgPolicy.check("", null, null)))
        assertTrue(rejected(MergeArgPolicy.check("a b", null, null)))
        assertTrue(rejected(MergeArgPolicy.check("a;rm -rf /", null, null)))
        assertTrue(rejected(MergeArgPolicy.check("a\$(whoami)", null, null)))
    }

    @Test fun `a leading-dash module is rejected`() {
        assertTrue(rejected(MergeArgPolicy.check("ok", "--init-script", null)))
    }

    @Test fun `the rejection reason is descriptive`() {
        val r = MergeArgPolicy.check("-X", null, null)
        assertTrue(r is MergeArgPolicy.Rejected && (r.reason.contains("option") || r.reason.contains("'-'")))
        assertEquals(MergeArgPolicy.Ok, MergeArgPolicy.check("clean-branch", null, null))
    }
}
