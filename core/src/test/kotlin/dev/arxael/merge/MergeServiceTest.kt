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
 * Daemon-side wiring: register a project, submit PRs, and confirm they flow through the orchestrator and
 * land on the shared main — exercised through the warm executor (noop adapter => the gate is always green,
 * so this isolates the register/submit/land plumbing; revert/attribution logic is covered in
 * [MergeOrchestratorTest] against the fake gate).
 */
class MergeServiceTest {
    private val tmp: Path = Files.createTempDirectory("arxael-mergesvc-test")
    private val bare: Path = tmp.resolve("bare.git")
    private val events = EventLog(tmp.resolve("events.jsonl"))
    private val modules = listOf(":mod1", ":mod2")
    private var executor: WarmExecutor? = null
    private var svc: MergeService? = null

    private fun dirOf(m: String) = m.removePrefix(":")
    private fun g(cwd: Path, vararg a: String) = GitOps.git(cwd, "-c", "user.name=t", "-c", "user.email=t@t", *a)

    private fun cfg() = BoxConfig(
        cores = 2, warmServers = 4, agentsPerCore = 1.0, coreBound = 2, memBound = 2, maxConcurrent = 2,
        bindingConstraint = "override", usableRamMb = 8000, ramHeadroomMb = 1000, perBuildFootprintMb = 1536,
        heapPerServerMb = 256, buildWorkers = 1, buildCache = false, perWorktreeHome = false,
        gradleHome = Path.of("/opt/gradle/none"), stateDir = tmp, port = 0, watchdogIntervalMs = 2000,
        acquireTimeoutMs = 2000, reservedHigh = 1, daemonIdleSec = 120,
    )

    private fun seedRepo() {
        val seed = tmp.resolve("seed"); Files.createDirectories(seed)
        GitOps.git(seed, "-c", "init.defaultBranch=main", "init", "-q")
        for (m in modules) {
            val d = seed.resolve(dirOf(m)); Files.createDirectories(d)
            Files.writeString(d.resolve("code.txt"), "init")
        }
        g(seed, "add", "-A"); g(seed, "commit", "-q", "-m", "init")
        GitOps.git(tmp, "clone", "-q", "--bare", seed.toString(), bare.toString())
    }

    private fun createPr(branch: String, module: String, content: String) {
        val wt = tmp.resolve("agent-$branch")
        GitOps.git(bare, "worktree", "add", "-q", "--detach", wt.toString(), "main")
        g(wt, "checkout", "-q", "-B", branch, "main")
        Files.writeString(wt.resolve("${dirOf(module)}/code.txt"), content)
        g(wt, "add", "-A"); g(wt, "commit", "-q", "-m", branch)
        GitOps.git(bare, "worktree", "remove", "--force", wt.toString())
    }

    private fun mainContent(module: String): String {
        val v = tmp.resolve("verify-${System.nanoTime()}")
        GitOps.git(bare, "worktree", "add", "-q", "--detach", v.toString(), "main")
        val c = Files.readString(v.resolve("${dirOf(module)}/code.txt"))
        GitOps.git(bare, "worktree", "remove", "--force", v.toString())
        return c
    }

    @AfterTest
    fun cleanup() {
        svc?.shutdown(); executor?.shutdown(); events.close()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    @Test
    fun `register then submit lands PRs on the shared main`() {
        seedRepo()
        val ex = WarmExecutor(cfg(), AdapterRegistry.default(), events).also { executor = it }
        val service = MergeService(cfg(), ex, events, gateAdapter = "noop").also { svc = it }

        assertFalse(service.submit("x", ":mod1", null), "submit before register is a no-op")

        val reg = service.register(bare.toString(), modules.associateWith { emptySet() }, thresholdSpec = 4, gateCount = 2)
        assertEquals(2, reg.modules)
        assertTrue(service.isRegistered())

        createPr("p1", ":mod1", "mod1 v2")
        createPr("p2", ":mod2", "mod2 v2")
        assertTrue(service.submit("p1", ":mod1", "agentA"))
        assertTrue(service.submit("p2", ":mod2", "agentB"))

        // poll until both land (optimistic path; noop gate is green so no reverts)
        val deadline = System.nanoTime() + 30_000L * 1_000_000
        while (System.nanoTime() < deadline && (service.status()?.get("landed") as? Int ?: 0) < 2) Thread.sleep(20)

        val s = service.status()!!
        assertEquals(2, s["landed"])
        assertEquals(0, s["reverts"])
        assertEquals("mod1 v2", mainContent(":mod1"))
        assertEquals("mod2 v2", mainContent(":mod2"))
    }
}
