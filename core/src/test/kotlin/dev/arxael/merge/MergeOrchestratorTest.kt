package dev.arxael.merge

import dev.arxael.eventlog.EventLog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end orchestrator correctness against a REAL git repo (the merge/revert engine that ships) with a
 * deterministic fake gate (a module's file containing "BAD" => that module's test fails). This is where the
 * money properties are proven: good PRs land, bad PRs are reverted/bounced, and **main is never left broken**
 * — the whole point of the design. Fast and hermetic: no gradle, no network.
 */
class MergeOrchestratorTest {
    private val tmp: Path = Files.createTempDirectory("arxael-merge-test")
    private val bare: Path = tmp.resolve("bare.git")
    private val events = EventLog(tmp.resolve("events.jsonl"))
    private val allModules = listOf(":mod1", ":mod2", ":mod3")
    private var orch: MergeOrchestrator? = null

    private fun dirOf(m: String) = m.removePrefix(":")

    /** Fake gate: a module is "red" iff its code.txt (in the given worktree state) contains "BAD". */
    private val gate = MergeGate { wt, modules, _ ->
        val check = modules ?: allModules.toSet()
        val failed = check.filter { m ->
            val f = wt.resolve("${dirOf(m)}/code.txt")
            Files.exists(f) && Files.readString(f).contains("BAD")
        }.toSet()
        GateResult(failed.isEmpty(), failed)
    }

    private fun g(cwd: Path, vararg a: String) =
        GitOps.git(cwd, "-c", "user.name=t", "-c", "user.email=t@t", *a)

    private fun setup(
        journal: PrJournal? = null,
        forwardDeps: Map<String, Set<String>> = allModules.associateWith { emptySet() },
        gate: MergeGate = this.gate,
        confirmThreshold: Int = 0,
        moduleDirs: Map<String, String> = emptyMap(),
    ): MergeOrchestrator {
        val seed = tmp.resolve("seed")
        Files.createDirectories(seed)
        GitOps.git(seed, "-c", "init.defaultBranch=main", "init", "-q")
        for (m in allModules) {
            val d = seed.resolve(dirOf(m)); Files.createDirectories(d)
            Files.writeString(d.resolve("code.txt"), "init")
        }
        g(seed, "add", "-A"); g(seed, "commit", "-q", "-m", "init")
        GitOps.git(tmp, "clone", "-q", "--bare", seed.toString(), bare.toString())

        val integ = tmp.resolve("integ")
        GitOps.git(bare, "worktree", "add", "-q", "--detach", integ.toString(), "main")
        val gates = (0..1).map { tmp.resolve("gate$it") }
        gates.forEach { GitOps.git(bare, "worktree", "add", "-q", "--detach", it.toString(), "main") }

        val router = MergeRouter(MergeRouter.reverseDeps(forwardDeps), threshold = 4)
        return MergeOrchestrator(bare, integ, gates, router, gate, events, batchCap = 8, journal = journal,
            confirmThreshold = confirmThreshold, moduleDirs = moduleDirs).also { orch = it }
    }

    /** Create a PR branch in the bare repo: edit one module's code.txt to [content]. */
    private fun createPr(branch: String, module: String, content: String): PullRequest {
        val wt = tmp.resolve("agent-$branch")
        GitOps.git(bare, "worktree", "add", "-q", "--detach", wt.toString(), "main")
        g(wt, "checkout", "-q", "-B", branch, "main")
        Files.writeString(wt.resolve("${dirOf(module)}/code.txt"), content)
        g(wt, "add", "-A"); g(wt, "commit", "-q", "-m", branch)
        GitOps.git(bare, "worktree", "remove", "--force", wt.toString())
        return PullRequest(branch = branch, module = module)
    }

    /** Create a null-module PR that adds a unique [filename] (so PRs never textually conflict). */
    private fun createPrFile(branch: String, filename: String, content: String): PullRequest {
        val wt = tmp.resolve("agent-$branch")
        GitOps.git(bare, "worktree", "add", "-q", "--detach", wt.toString(), "main")
        g(wt, "checkout", "-q", "-B", branch, "main")
        Files.writeString(wt.resolve(filename), content)
        g(wt, "add", "-A"); g(wt, "commit", "-q", "-m", branch)
        GitOps.git(bare, "worktree", "remove", "--force", wt.toString())
        return PullRequest(branch = branch, module = null) // null module -> unattributable -> bisection path
    }

    private fun fileOnMain(filename: String): Boolean {
        val v = tmp.resolve("vf-${System.nanoTime()}")
        GitOps.git(bare, "worktree", "add", "-q", "--detach", v.toString(), "main")
        val present = Files.exists(v.resolve(filename))
        GitOps.git(bare, "worktree", "remove", "--force", v.toString())
        return present
    }

    @Test
    fun `unattributable red batch bisects - lands the good PRs, bounces the bad, bounded gate calls`() {
        val gateCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val bisectGate = MergeGate { wt, _, _ ->
            gateCalls.incrementAndGet()
            val bad = Files.walk(wt).use { s ->
                s.filter { Files.isRegularFile(it) && !it.toString().contains("/.git/") }
                    .anyMatch { runCatching { Files.readString(it) }.getOrDefault("").contains("BAD") }
            }
            GateResult(green = !bad) // no failedModules -> unattributable -> exercises the bisection fallback
        }
        val o = setup(gate = bisectGate)
        val prs = (0 until 8).map { i -> createPrFile("p$i", "f$i.txt", if (i == 5) "BAD" else "ok-$i") }
        gateCalls.set(0)
        o.processBatch(prs)

        for (i in 0 until 8) {
            if (i == 5) assertFalse(fileOnMain("f$i.txt"), "bad PR p5 must NOT land")
            else assertTrue(fileOnMain("f$i.txt"), "good PR p$i must land")
        }
        // bisection is sub-linear: the old per-PR fallback would cost 1 (batch) + 8 (each) = 9 gates;
        // bisection isolates one bad PR in ~7 and lands clean halves whole.
        assertTrue(gateCalls.get() <= 8, "bisection should bound gate calls below linear; was ${gateCalls.get()}")
    }

    @Test
    fun `submit is rejected after shutdown, not silently queued into a stopped loop`() {
        val o = setup()
        assertTrue(o.submit(PullRequest("a", ":mod1")), "accepts a submit before shutdown")
        o.shutdown()
        assertFalse(o.submit(PullRequest("b", ":mod1")), "rejects a submit after shutdown (caller retries)")
    }

    @Test
    fun `a batch does not clobber main if it moved during the gate (compare-and-set)`() {
        // The gate, while running OFF the git lock, moves main out from under the batch — standing in for a
        // concurrent async optimistic revert. The CAS land must detect this and NOT clobber the move.
        val racyGate = MergeGate { _, _, _ ->
            createPrFile("sentinel", "Other.kt", "class O")        // a distinct commit (bare exists by now)
            GitOps.setBranch(bare, "main", GitOps.rev(bare, "sentinel"))
            GateResult(green = true)
        }
        val o = setup(gate = racyGate)
        o.processBatch(listOf(createPrFile("p1", "Code.kt", "class C")))
        assertEquals(GitOps.rev(bare, "sentinel"), GitOps.rev(bare, "main"),
            "the concurrent move was preserved, not clobbered by the stale batch head")
        assertEquals(0, o.snapshot()["landed"], "the stale batch did not land")
    }

    private val moduleDirs = mapOf(":mod1" to "mod1", ":mod2" to "mod2", ":mod3" to "mod3")

    @Test
    fun `a doc-only change lands WITHOUT being gated (change-aware skip)`() {
        val o = setup(moduleDirs = moduleDirs)
        // a PR that only adds a README.md (inert) -> must land without running any gate
        o.processBatch(listOf(createPrFile("docs", "README.md", "hello")))
        assertTrue(fileOnMain("README.md"), "the doc change landed")
        val snap = o.snapshot()
        assertEquals(1, snap["gatesSkipped"], "the gate was skipped")
        assertEquals(0, snap["integTests"], "no test was run for the inert change")
    }

    @Test
    fun `a single-module change is gated against only that module's closure (derived from the diff)`() {
        val scopes = java.util.Collections.synchronizedList(mutableListOf<Set<String>?>())
        val recording = MergeGate { _, modules, _ -> scopes.add(modules); GateResult(green = true) }
        val o = setup(gate = recording, moduleDirs = moduleDirs)
        // module=null (agent didn't declare), but the diff touches only mod3 -> scope derived from the diff.
        // (use a .kt source path, not the fixture's code.txt — a .txt would correctly be treated as inert.)
        o.processBatch(listOf(createPrFile("m3", "mod3/Code.kt", "class C")))
        assertEquals(1, scopes.size)
        assertEquals(setOf(":mod3"), scopes[0], "scoped to the changed module's closure, not full (null)")
    }

    /** Read a module's code.txt at the current bare `main`. */
    private fun mainContent(module: String): String {
        val v = tmp.resolve("verify-${System.nanoTime()}")
        GitOps.git(bare, "worktree", "add", "-q", "--detach", v.toString(), "main")
        val c = Files.readString(v.resolve("${dirOf(module)}/code.txt"))
        GitOps.git(bare, "worktree", "remove", "--force", v.toString())
        return c
    }

    @AfterTest
    fun cleanup() {
        orch?.shutdown()
        events.close()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    @Test
    fun `optimistic path lands good PRs and auto-reverts a bad one without touching the others`() {
        val o = setup()
        val good1 = createPr("a1", ":mod1", "mod1 v2 ok")
        val bad = createPr("a2", ":mod2", "BAD change")
        val good3 = createPr("a3", ":mod3", "mod3 v2 ok")

        o.processBatch(listOf(good1, bad, good3))
        assertTrue(o.awaitQuiescent(30_000), "orchestrator did not settle")

        val s = o.snapshot()
        assertEquals(3, s["optLanded"], "all three start by landing optimistically")
        assertEquals(1, s["reverts"], "exactly the bad PR is reverted")
        assertEquals(2, s["landed"], "net landed = the two good PRs")

        // the good changes survive on main; the bad change was reverted off it
        assertEquals("mod1 v2 ok", mainContent(":mod1"))
        assertEquals("mod3 v2 ok", mainContent(":mod3"))
        assertFalse(mainContent(":mod2").contains("BAD"), "main must not be left broken")
        // and main is fully green per the gate
        assertTrue(gate.test(integForVerify(), null, "final").green, "main must end green")
    }

    @Test
    fun `batched path attributes the culprit, lands the rest, never lands red`() {
        // force the batched path: threshold 0 => every closure (>=1) is BATCHED
        val o = setup()
        // rebuild orchestrator with threshold 0 (batched) — reuse same worktrees
        val integ = tmp.resolve("integ")
        val gates = (0..1).map { tmp.resolve("gate$it") }
        val router0 = MergeRouter(MergeRouter.reverseDeps(allModules.associateWith { emptySet<String>() }), threshold = 0)
        o.shutdown()
        val ob = MergeOrchestrator(bare, integ, gates, router0, gate, events, batchCap = 8).also { orch = it }

        val g1 = createPr("b1", ":mod1", "mod1 v2 ok")
        val bad = createPr("b2", ":mod2", "BAD change")
        val g3 = createPr("b3", ":mod3", "mod3 v2 ok")

        ob.processBatch(listOf(g1, bad, g3))   // batched: one test fails -> attribute mod2 -> bounce, re-test rest

        val s = ob.snapshot()
        assertEquals(2, s["landed"], "the two good PRs land")
        assertEquals(1, s["bouncedSemantic"], "the culprit is bounced, not landed")
        assertEquals(0, s["reverts"], "batched never breaks main, so never reverts")
        assertEquals("mod1 v2 ok", mainContent(":mod1"))
        assertEquals("mod3 v2 ok", mainContent(":mod3"))
        assertEquals("init", mainContent(":mod2"), "the bad change never reached main")
        assertTrue(gate.test(integForVerify(), null, "final").green)
    }

    @Test
    fun `batched attribution re-test widens scope so undeclared coupling can't land a red change`() {
        // PR-A changes :mod1 with a break that, via UNDECLARED coupling (no declared edge :mod2 -> :mod1),
        // fails :mod2's tests. PR-B owns :mod2. The full-batch gate (scope {:mod1,:mod2}) goes red on :mod2,
        // so attribution blames B and bounces it. The re-test of the remainder [A] must gate over AT LEAST the
        // modules that were red (:mod2) — NOT narrow to A's declared closure {:mod1}, which would miss the break
        // and land A's red change. Proves main is never left red even under undeclared cross-module coupling.
        val crossModuleGate = MergeGate { wt, modules, _ ->
            val check = modules ?: allModules.toSet()
            val mod1Broken = runCatching { Files.readString(wt.resolve("mod1/code.txt")).contains("BREAKS_DEP") }.getOrDefault(false)
            val failed = check.filter { m ->
                val ownBad = runCatching { Files.readString(wt.resolve("${dirOf(m)}/code.txt")).contains("BAD") }.getOrDefault(false)
                ownBad || (m == ":mod2" && mod1Broken) // :mod2 only shows red when it's actually IN the gate scope
            }.toSet()
            GateResult(failed.isEmpty(), failed)
        }
        setup()
        val integ = tmp.resolve("integ")
        val gates = (0..1).map { tmp.resolve("gate$it") }
        // independent modules, threshold 0 => every PR routes BATCHED; :mod1's declared closure is {:mod1} only
        val router0 = MergeRouter(MergeRouter.reverseDeps(allModules.associateWith { emptySet<String>() }), threshold = 0)
        orch!!.shutdown()
        val ob = MergeOrchestrator(bare, integ, gates, router0, crossModuleGate, events, batchCap = 8).also { orch = it }

        val a = createPr("a", ":mod1", "BREAKS_DEP")  // breaks :mod2 via undeclared coupling (no "BAD" -> own tests pass)
        val b = createPr("b", ":mod2", "mod2 v2 ok")  // innocent owner of the failing module

        ob.processBatch(listOf(a, b))

        assertEquals(0, ob.snapshot()["landed"], "the real culprit A must NOT land its red change")
        assertEquals("init", mainContent(":mod1"), "A's breaking change never reached main")
        assertEquals("init", mainContent(":mod2"), "main is clean for :mod2 too")
        assertTrue(crossModuleGate.test(integForVerify(), null, "final").green, "main must end green")
    }

    @Test
    fun `optimistic gate catches a change that breaks a DEPENDENT module (not just its own)`() {
        // :mod2 depends on :mod1, so :mod1's affected closure is {:mod1, :mod2} (size 2 <= threshold -> optimistic).
        // Gate rule: a change to :mod1 that writes "BREAKS_DEP" passes :mod1's own tests but FAILS :mod2's.
        // Old behaviour (gate tested only the changed module) would miss this and leave main broken; the fix
        // (gate the whole closure) must catch it and revert.
        val crossModuleGate = MergeGate { wt, modules, _ ->
            val check = modules ?: allModules.toSet()
            val mod1Broken = runCatching { Files.readString(wt.resolve("mod1/code.txt")).contains("BREAKS_DEP") }.getOrDefault(false)
            val failed = check.filter { m ->
                val own = runCatching { Files.readString(wt.resolve("${dirOf(m)}/code.txt")).contains("BAD") }.getOrDefault(false)
                own || (m == ":mod2" && mod1Broken) // :mod2 fails when :mod1 carries the breaking change
            }.toSet()
            GateResult(failed.isEmpty(), failed)
        }
        val o = setup(forwardDeps = mapOf(":mod1" to emptySet(), ":mod2" to setOf(":mod1"), ":mod3" to emptySet()),
                      gate = crossModuleGate)
        createPr("p1", ":mod1", "BREAKS_DEP")   // :mod1's own tests pass; it breaks :mod2

        o.processBatch(listOf(PullRequest("p1", ":mod1")))  // synchronous land; async gate then reverts
        assertTrue(o.awaitQuiescent(30_000), "did not settle")

        assertEquals(1, o.snapshot()["optLanded"], "it lands optimistically first")
        assertEquals(1, o.snapshot()["reverts"], "then the closure-scoped gate catches the dependent break and reverts")
        assertEquals(0, o.snapshot()["landed"], "net: nothing left on main")
        assertEquals("init", mainContent(":mod1"), "main was restored (not left broken)")
    }

    @Test
    fun `a persistently inconclusive gate gives up after a bounded number of retries (no infinite loop)`() {
        // gate that NEVER reaches a verdict (simulates a wedged/OVERLOADED substrate)
        val alwaysInconclusive = MergeGate { _, _, _ -> GateResult(green = false, conclusive = false) }
        val o = setup(gate = alwaysInconclusive)
        createPr("p1", ":mod1", "mod1 v2")
        o.processBatch(listOf(PullRequest("p1", ":mod1"))) // synchronous land; the re-test chain runs on the gate pool
        // MUST terminate (the retry cap guarantees it) rather than retry forever
        assertTrue(o.awaitQuiescent(30_000), "should settle once retries are exhausted, not loop forever")
        val s = o.snapshot()
        assertEquals(0, s["landed"], "nothing stays on main (every optimistic land was reverted)")
        assertTrue((s["errors"] as Int) >= 1, "retry exhaustion is surfaced as an error")
        assertEquals("init", mainContent(":mod1"), "main is left clean")
    }

    @Test
    fun `verify-then-trust - a module is gated FULL until confirmed, then narrows to its closure`() {
        val scopes = java.util.concurrent.CopyOnWriteArrayList<Set<String>?>()
        val recordingGate = MergeGate { _, modules, _ -> scopes.add(modules); GateResult(green = true) }
        val o = setup(gate = recordingGate, confirmThreshold = 2) // confirm twice, then trust the closure
        for (i in 1..3) {
            createPr("c$i", ":mod1", "mod1 v$i")
            o.processBatch(listOf(PullRequest("c$i", ":mod1")))
            assertTrue(o.awaitQuiescent(30_000), "settle PR $i")
        }
        assertEquals(3, scopes.size)
        assertEquals(null, scopes[0], "1st change to the module -> FULL gate (null scope)")
        assertEquals(null, scopes[1], "2nd change -> still FULL (confirming)")
        assertEquals(setOf(":mod1"), scopes[2], "after 2 clean confirmations -> trust the narrow closure")
    }

    @Test
    fun `recover replays unfinished PRs from a prior run's journal and lands them`() {
        val journalPath = tmp.resolve("crash-journal")
        // A PRIOR run journaled two submits then crashed before finishing them (DONE never written).
        PrJournal(journalPath).apply {
            submit(PullRequest("r1", ":mod1"))
            submit(PullRequest("r2", ":mod2"))
        }
        // The (single) orchestrator owns its worktrees; give it the SAME journal so recover() replays them.
        val o = setup(journal = PrJournal(journalPath))
        createPr("r1", ":mod1", "mod1 recovered")
        createPr("r2", ":mod2", "mod2 recovered")

        o.start()
        o.recover()
        assertTrue(o.awaitQuiescent(30_000), "recovered orchestrator did not settle")

        assertEquals(2, o.snapshot()["landed"], "both recovered PRs should land")
        assertEquals("mod1 recovered", mainContent(":mod1"))
        assertEquals("mod2 recovered", mainContent(":mod2"))
        assertEquals(emptyList(), PrJournal(journalPath).pending(), "journal shows nothing pending after recovery")
    }

    /** A worktree checked out to main for a final full-gate assertion. */
    private fun integForVerify(): Path {
        val v = tmp.resolve("finalcheck")
        if (!Files.exists(v)) GitOps.git(bare, "worktree", "add", "-q", "--detach", v.toString(), "main")
        else { GitOps.git(v, "checkout", "-q", "--detach", "main"); GitOps.git(v, "reset", "-q", "--hard", "main") }
        return v
    }
}
