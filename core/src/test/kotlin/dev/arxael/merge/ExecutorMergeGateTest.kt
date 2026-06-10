package dev.arxael.merge

import dev.arxael.adapter.AdapterRegistry
import dev.arxael.config.BoxConfig
import dev.arxael.eventlog.EventLog
import dev.arxael.executor.WarmExecutor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The production gate over the warm executor. Uses the NoopAdapter (always SUCCESS) to confirm the
 * success-path mapping and that the gate routes on the reserved "high" lane without wedging.
 */
class ExecutorMergeGateTest {
    private val tmp: Path = Files.createTempDirectory("arxael-gate-test")
    private val events = EventLog(tmp.resolve("events.jsonl"))

    @AfterTest
    fun cleanup() {
        events.close()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    private fun cfg() = BoxConfig(
        cores = 2, warmServers = 4, agentsPerCore = 1.0, coreBound = 2, memBound = 2, maxConcurrent = 2,
        bindingConstraint = "override", usableRamMb = 8000, ramHeadroomMb = 1000, perBuildFootprintMb = 1536,
        heapPerServerMb = 256, buildWorkers = 1, buildCache = false, perWorktreeHome = false,
        gradleHome = Path.of("/opt/gradle/none"), stateDir = tmp, port = 0, watchdogIntervalMs = 2000,
        acquireTimeoutMs = 2000, reservedHigh = 1, daemonIdleSec = 120,
    )

    @Test
    fun `a passing build maps to a green gate result`() {
        val executor = WarmExecutor(cfg(), AdapterRegistry.default(), events)
        try {
            val gate = ExecutorMergeGate(executor, adapter = "noop")
            val full = gate.test(tmp.resolve("wt"), null, "integ")
            assertTrue(full.green, "noop build succeeds -> gate green")
            assertEquals(emptySet(), full.failedModules)

            // module-scoped form (incremental) also resolves through the executor and is green
            val scoped = gate.test(tmp.resolve("wt"), setOf(":mod1", ":mod2"), "opt-gate")
            assertTrue(scoped.green)
        } finally { executor.shutdown() }
    }

    @Test
    fun `gate tasks are gradle-incremental but full-suite for other languages`() {
        // gradle: full lifecycle task when no modules, incremental module-scoped tasks otherwise
        assertEquals(listOf("test"), ExecutorMergeGate.gateTasks("gradle", null))
        assertEquals(listOf("test"), ExecutorMergeGate.gateTasks("gradle", emptySet()))
        assertEquals(listOf(":mod1:test", ":mod2:test"),
            ExecutorMergeGate.gateTasks("gradle", linkedSetOf(":mod1", ":mod2")))
        // non-JVM adapters: empty tasks -> the CommandAdapter runs its default full-suite command,
        // and module-scoping is ignored (no module-scoped task graph exists)
        for (a in listOf("pytest", "cargo", "go", "npm", "vitest", "make")) {
            assertEquals(emptyList(), ExecutorMergeGate.gateTasks(a, null), "$a full")
            assertEquals(emptyList(), ExecutorMergeGate.gateTasks(a, setOf(":mod1")), "$a ignores modules")
        }
    }

    @Test
    fun `gradle gate runs with --continue so every failed module is reported in one pass`() {
        assertEquals(listOf("--continue"), ExecutorMergeGate.gateArgs("gradle"))
        for (a in listOf("pytest", "cargo", "go", "npm", "make")) {
            assertEquals(emptyList(), ExecutorMergeGate.gateArgs(a), "$a takes no gradle flags")
        }
    }

    @Test
    fun `outcome maps to the right gate verdict for every status`() {
        // SUCCESS -> green, conclusive
        ExecutorMergeGate.toGateResult("SUCCESS", "").let {
            assertTrue(it.green); assertTrue(it.conclusive); assertEquals(emptySet(), it.failedModules)
        }
        // FAILED -> red, conclusive, with attributed culprit modules from the output
        ExecutorMergeGate.toGateResult("FAILED", "> Task :mod3:test FAILED").let {
            assertFalse(it.green); assertTrue(it.conclusive); assertEquals(setOf(":mod3"), it.failedModules)
        }
        // OVERLOADED / ERROR / REJECTED -> red but INCONCLUSIVE (retry, don't bounce a possibly-good PR)
        for (s in listOf("OVERLOADED", "ERROR", "REJECTED")) {
            ExecutorMergeGate.toGateResult(s, "").let {
                assertFalse(it.green, "$s not green"); assertFalse(it.conclusive, "$s inconclusive")
                assertEquals(emptySet(), it.failedModules)
            }
        }
    }

    @Test
    fun `a FAILED that is a transient infra fault (OOM, killed worker) is inconclusive, not a conclusive red`() {
        // C4: a FAILED with NO parseable task failure but infra-death signatures is a transient resource event,
        // not a real test failure -> inconclusive (retry), so a good PR isn't reverted/bounced on an OOM blip.
        val infra = listOf(
            "java.lang.OutOfMemoryError: Java heap space",
            "Process 'Gradle Test Executor 7' finished with non-zero exit value 137",
            "java.lang.OutOfMemoryError: Metaspace",
            "Gradle build daemon disappeared unexpectedly",
        )
        for (out in infra) {
            ExecutorMergeGate.toGateResult("FAILED", out).let {
                assertFalse(it.green); assertFalse(it.conclusive, "infra-fault FAILED must be inconclusive: $out")
            }
            assertTrue(ExecutorMergeGate.looksLikeInfraFault(out), "should flag infra: $out")
        }
        // but if a real task FAILED line IS present, it's a CONCLUSIVE red even alongside an infra string —
        // a genuine failure was attributed, so don't retry forever.
        ExecutorMergeGate.toGateResult("FAILED", "> Task :mod2:test FAILED\njava.lang.OutOfMemoryError").let {
            assertFalse(it.green); assertTrue(it.conclusive, "an attributed task failure is conclusive")
            assertEquals(setOf(":mod2"), it.failedModules)
        }
        // an ordinary compile/test failure with no infra signature stays conclusive
        assertFalse(ExecutorMergeGate.looksLikeInfraFault("> Task :app:test FAILED\nexpected:<1> but was:<2>"))
    }
}
