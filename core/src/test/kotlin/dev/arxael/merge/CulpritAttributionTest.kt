package dev.arxael.merge

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Culprit attribution drives the "land the rest, bounce only the culprit" fast path — so a parse miss is
 * expensive in both directions: miss a real culprit and a red merge lands; over-attribute and good PRs get
 * bounced. These cases pin the failure-context-only behaviour and the multi/nested-module paths.
 */
class CulpritAttributionTest {

    @Test
    fun `empty or null output attributes nothing`() {
        assertEquals(emptySet(), CulpritAttribution.failedModules(null))
        assertEquals(emptySet(), CulpritAttribution.failedModules(""))
    }

    @Test
    fun `green output attributes nothing`() {
        val out = "> Task :mod3:test\n> Task :mod3:jacocoTestReport\nBUILD SUCCESSFUL in 6s"
        assertEquals(emptySet(), CulpritAttribution.failedModules(out))
    }

    @Test
    fun `a failed task line names its module`() {
        assertEquals(setOf(":mod3"), CulpritAttribution.failedModules("> Task :mod3:test FAILED"))
    }

    @Test
    fun `the execution-failed summary line names its module`() {
        val out = "FAILURE: Build failed with an exception.\n" +
            "* What went wrong:\nExecution failed for task ':mod7:test'."
        assertEquals(setOf(":mod7"), CulpritAttribution.failedModules(out))
    }

    @Test
    fun `the Task-FAILED and Execution-failed lines for the same module dedupe`() {
        val out = "> Task :mod3:test FAILED\n...\nExecution failed for task ':mod3:test'."
        assertEquals(setOf(":mod3"), CulpritAttribution.failedModules(out))
    }

    @Test
    fun `multiple distinct failed modules are all attributed`() {
        val out = "> Task :mod3:test FAILED\n> Task :mod5:test FAILED\n" +
            "Execution failed for task ':mod3:test'."
        assertEquals(setOf(":mod3", ":mod5"), CulpritAttribution.failedModules(out))
    }

    @Test
    fun `nested module paths are preserved (not just flat modN)`() {
        assertEquals(setOf(":app:core"), CulpritAttribution.failedModules("> Task :app:core:test FAILED"))
    }

    @Test
    fun `a root-project task failure is unattributable to a subproject and dropped`() {
        // ":test FAILED" -> module would be root (""), which we can't attribute -> empty (all-suspect).
        assertEquals(emptySet(), CulpritAttribution.failedModules("> Task :test FAILED"))
    }

    @Test
    fun `a Task-FAILED echoed inside test stdout (not at line start) does NOT bounce an innocent module`() {
        // A test logs / asserts on a build-log string mid-line; only :real actually failed. Anchoring the regex
        // to line start prevents attributing :innocent and bouncing its (good) PR.
        val out = buildString {
            appendLine("> Task :real:test FAILED")
            appendLine("    com.example.LogTest > formats line: expected \"> Task :innocent:test FAILED\" but was ...")
            appendLine("some prose mentioning Execution failed for task ':alsoInnocent:test' in passing")
            appendLine("Execution failed for task ':real:test'.")
        }
        assertEquals(setOf(":real"), CulpritAttribution.failedModules(out))
    }

    @Test
    fun `indented or prefixed Task lines (stack frames, log wrappers) are not attributed`() {
        val out = "\tat builder.run(> Task :phantom:test FAILED)\n   > Task :alsoNot:test FAILED"
        assertEquals(emptySet(), CulpritAttribution.failedModules(out))
    }
}
