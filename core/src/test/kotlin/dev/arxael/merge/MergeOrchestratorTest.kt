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
        gateBackpressure: Boolean = true,
        gateFillRouting: Boolean = false,
        gateFillHysteresis: Boolean = false,
        gateCount: Int = 2,
        batchCap: Int = 8,
        batchCapAware: Boolean = false,
        batchCapDominanceFactor: Double = 2.0,
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
        val gates = (0 until maxOf(1, gateCount)).map { tmp.resolve("gate$it") }
        gates.forEach { GitOps.git(bare, "worktree", "add", "-q", "--detach", it.toString(), "main") }

        val router = MergeRouter(MergeRouter.reverseDeps(forwardDeps), threshold = 4)
        return MergeOrchestrator(bare, integ, gates, router, gate, events, batchCap = batchCap, journal = journal,
            confirmThreshold = confirmThreshold, moduleDirs = moduleDirs, gateBackpressure = gateBackpressure,
            gateFillRouting = gateFillRouting, gateFillHysteresis = gateFillHysteresis,
            batchCapAware = batchCapAware, batchCapDominanceFactor = batchCapDominanceFactor).also { orch = it }
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
        val scopes = java.util.concurrent.CopyOnWriteArrayList<Set<String>?>()
        val recording = MergeGate { _, modules, _ -> scopes.add(modules); GateResult(green = true) }
        val o = setup(gate = recording, moduleDirs = moduleDirs)
        // module=null (agent didn't declare), but the diff touches only mod3 -> the module is INFERRED from the
        // diff, the PR takes the optimistic fast path, and its gate is scoped to that module's closure.
        // (use a .kt source path, not the fixture's code.txt — a .txt would correctly be treated as inert.)
        o.processBatch(listOf(createPrFile("m3", "mod3/Code.kt", "class C")))
        assertTrue(o.awaitQuiescent(30_000), "the inferred-module optimistic gate did not settle")
        assertEquals(1, scopes.size)
        assertEquals(setOf(":mod3"), scopes[0], "scoped to the changed module's closure, not full (null)")
    }

    @Test
    fun `a branch missing from the hub is reported distinctly, not as a textual conflict`() {
        val o = setup()
        o.processBatch(listOf(PullRequest("ghost", ":mod1"))) // never created in the bare repo
        val s = o.snapshot()
        assertEquals(1, s["branchMissing"], "reported as a missing branch")
        assertEquals(0, s["bouncedTextual"], "NOT mislabelled a textual conflict")
        assertEquals(0, s["landed"], "nothing landed")
        val st = o.prStatus("ghost")
        assertEquals("missing", st?.state)
        assertTrue(st?.terminal == true)
    }

    /** Create a PR that writes [content] to [relPath] (a source file, so it's NOT inert) and DECLARES [module]
     *  (which may deliberately differ from the directory the file lives in, to model a mis-declaring agent). */
    private fun createPrAt(branch: String, relPath: String, content: String, module: String?): PullRequest {
        val wt = tmp.resolve("agent-$branch")
        GitOps.git(bare, "worktree", "add", "-q", "--detach", wt.toString(), "main")
        g(wt, "checkout", "-q", "-B", branch, "main")
        val f = wt.resolve(relPath); Files.createDirectories(f.parent); Files.writeString(f, content)
        g(wt, "add", "-A"); g(wt, "commit", "-q", "-m", branch)
        GitOps.git(bare, "worktree", "remove", "--force", wt.toString())
        return PullRequest(branch = branch, module = module)
    }

    @Test
    fun `batched attribution blames the PR whose DIFF broke the module, not the mis-declarer`() {
        // C8: attribute by the PR's actual diff, not its declared module. A declares :mod2 but its diff only
        // touches :mod1 (clean); B declares :mod1 but its diff breaks :mod2 (BAD). Declared-module attribution
        // would bounce the INNOCENT A (it declared the failing module); diff attribution correctly bounces B.
        // Use .kt source files (a .txt would be inert -> the gate would be skipped). The gate flags a module
        // red iff any file under it contains "BAD".
        val ktGate = MergeGate { wt, modules, _ ->
            val check = modules ?: allModules.toSet()
            val failed = check.filter { m ->
                val d = wt.resolve(dirOf(m))
                Files.exists(d) && Files.walk(d).use { s -> s.anyMatch { Files.isRegularFile(it) && runCatching { Files.readString(it) }.getOrDefault("").contains("BAD") } }
            }.toSet()
            GateResult(failed.isEmpty(), failed)
        }
        setup(moduleDirs = moduleDirs)
        val integ = tmp.resolve("integ")
        val gates = (0..1).map { tmp.resolve("gate$it") }
        val router0 = MergeRouter(MergeRouter.reverseDeps(allModules.associateWith { emptySet<String>() }), threshold = 0)
        orch!!.shutdown()
        val ob = MergeOrchestrator(bare, integ, gates, router0, ktGate, events, batchCap = 8, moduleDirs = moduleDirs).also { orch = it }
        val a = createPrAt("a", "mod1/A.kt", "class A", ":mod2")  // diff -> :mod1 (clean), declares :mod2
        val b = createPrAt("b", "mod2/B.kt", "BAD", ":mod1")      // diff -> :mod2 (breaks it), declares :mod1
        ob.processBatch(listOf(a, b))
        val s = ob.snapshot()
        assertEquals(1, s["landed"], "the innocent mis-declarer A lands")
        assertEquals(1, s["bouncedSemantic"], "the real (diff) culprit B is bounced")
        assertEquals("bounced", ob.prStatus("b")?.state, "B (diff culprit) is bounced")
        assertEquals("landed", ob.prStatus("a")?.state, "A (innocent mis-declarer) lands")
    }

    @Test
    fun `a batch is NOT false-bounced when main was already red (C5)`() {
        // C5: main is left red on :mod2 (standing in for a prior optimistic revert that conflicted). A clean PR
        // touches :mod1; :mod2 depends on :mod1 so :mod2 is in the PR's gate scope and shows the PRE-EXISTING
        // red. The PR touched NO failed module, and mainBase is itself red -> the PR is innocent: it must be
        // RE-QUEUED, never bounced as the culprit (the cascade the fix prevents).
        val ktGate = MergeGate { wt, modules, _ ->
            val check = modules ?: allModules.toSet()
            val failed = check.filter { m ->
                val d = wt.resolve(dirOf(m))
                Files.exists(d) && Files.walk(d).use { s -> s.anyMatch { Files.isRegularFile(it) && runCatching { Files.readString(it) }.getOrDefault("").contains("BAD") } }
            }.toSet()
            GateResult(failed.isEmpty(), failed)
        }
        setup(moduleDirs = moduleDirs)
        val integ = tmp.resolve("integ")
        val gates = (0..1).map { tmp.resolve("gate$it") }
        // :mod2 depends on :mod1, so a change to :mod1 has closure {:mod1,:mod2}; threshold 0 -> BATCHED.
        val fwd = mapOf(":mod1" to emptySet(), ":mod2" to setOf(":mod1"), ":mod3" to emptySet<String>())
        val router0 = MergeRouter(MergeRouter.reverseDeps(fwd), threshold = 0)
        orch!!.shutdown()
        val ob = MergeOrchestrator(bare, integ, gates, router0, ktGate, events, batchCap = 8, moduleDirs = moduleDirs).also { orch = it }
        // poison main: a BAD source file in :mod2, forced onto main (a prior revert that conflicted would leave
        // exactly this — a red main no batch caused).
        createPrAt("poison", "mod2/Poison.kt", "BAD", null)
        GitOps.setBranch(bare, "main", GitOps.rev(bare, "poison"))
        // an innocent PR touching only :mod1
        ob.processBatch(listOf(createPrAt("g", "mod1/G.kt", "class G", null)))
        val s = ob.snapshot()
        assertEquals(0, s["bouncedSemantic"], "the innocent PR must NOT be bounced onto an already-red main")
        assertEquals(0, s["landed"], "and it must not land onto a red main either")
        assertTrue((s["queueDepth"] as Int) >= 1, "the innocent PR is re-queued (until main is repaired)")
        assertEquals("queued", ob.prStatus("g")?.state, "its status is queued (retry), not bounced")
    }

    @Test
    fun `per-PR status tracks a branch to its terminal outcome and is null for an unknown branch`() {
        val o = setup()
        createPr("ok1", ":mod1", "mod1 v2")
        o.processBatch(listOf(PullRequest("ok1", ":mod1")))
        assertTrue(o.awaitQuiescent(30_000))
        val st = o.prStatus("ok1")
        assertEquals("landed", st?.state); assertTrue(st?.terminal == true)
        assertEquals(null, o.prStatus("never-submitted"), "an unknown branch has no record")
    }

    @Test
    fun `H19 gate-fill hysteresis is sticky - enters at threshold, holds the regime down to half`() {
        // 8 gates -> gateCapacity 8 -> gateFillThreshold 8, hysteresis low = 8/2 = 4. Dwell band [4, 8).
        val o = setup(gateFillRouting = true, gateFillHysteresis = true, gateCount = 8)
        // From BATCH (wasOpt=false): must reach the full threshold to enter optimistic.
        assertFalse(o.gateFillNowOpt(7, wasOpt = false), "below threshold stays batch")
        assertTrue(o.gateFillNowOpt(8, wasOpt = false), "at threshold enters optimistic")
        // From OPTIMISTIC (wasOpt=true): STAY through the dwell band, leave only below the low band.
        assertTrue(o.gateFillNowOpt(7, wasOpt = true), "in dwell band stays optimistic (no flap)")
        assertTrue(o.gateFillNowOpt(4, wasOpt = true), "at low band still optimistic")
        assertFalse(o.gateFillNowOpt(3, wasOpt = true), "below low band drops to batch")
    }

    @Test
    fun `H19 hysteresis OFF is byte-for-byte the legacy binary trigger (wasOpt irrelevant)`() {
        // Flag off -> gateFillLow == gateFillThreshold (8) -> the decision is signal>=8 regardless of prior regime.
        val o = setup(gateFillRouting = true, gateFillHysteresis = false, gateCount = 8)
        for (signal in intArrayOf(0, 7, 8, 9, 16)) {
            assertEquals(
                o.gateFillNowOpt(signal, wasOpt = false), o.gateFillNowOpt(signal, wasOpt = true),
                "with hysteresis off the prior regime must NOT change the decision at signal=$signal"
            )
            assertEquals(signal >= 8, o.gateFillNowOpt(signal, wasOpt = false), "legacy: opt iff signal>=threshold at $signal")
        }
    }

    @Test
    fun `H23 batchCap-aware forces batch only when batchCap dominates the pool`() {
        // gateCount=4 -> gateCapacity 4, factor 2 -> dominance boundary at batchCap > 8.
        assertTrue(setup(gateFillRouting = true, batchCapAware = true, gateCount = 4, batchCap = 16).batchCapDominates(),
            "batchCap 16 > 2*4 dominates -> force batch")
        assertFalse(setup(gateFillRouting = true, batchCapAware = true, gateCount = 4, batchCap = 8).batchCapDominates(),
            "batchCap 8 == 2*4 does NOT dominate (strict >)")
        assertFalse(setup(gateFillRouting = true, batchCapAware = true, gateCount = 4, batchCap = 4).batchCapDominates(),
            "batchCap 4 < 2*4 does not dominate -> bare gate-fill")
    }

    @Test
    fun `H23 off - batchCapDominates is always false regardless of batchCap (zero behavior change)`() {
        // Flag off: even an extreme batchCap must not trip the dominance branch -> bare gate-fill path unchanged.
        assertFalse(setup(gateFillRouting = true, batchCapAware = false, gateCount = 4, batchCap = 128).batchCapDominates())
        assertFalse(setup(gateFillRouting = true, batchCapAware = false, gateCount = 2, batchCap = 64).batchCapDominates())
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
        // Pure optimistic mechanics: opt out of H1c' backpressure so all three take the fast path regardless of the
        // tiny 2-worktree pool (backpressure-on behavior is covered by the other tests + the bench saturation A/Bs).
        val o = setup(gateBackpressure = false)
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
        // recovered PRs carry a submittedAtNanos from the PRIOR process (no epoch across restarts), so their
        // time-to-land is meaningless and must NOT pollute the p50 metric.
        assertEquals(0L, o.snapshot()["p50TimeToLandMs"], "recovered lands are excluded from the time-to-land p50")
    }

    // ---- H7: conflict-adaptive routing threshold (pure decision) ----

    @Test
    fun conflictThreshold_warmsUpAsBatchBelowMinSamples() {
        // Below CONFLICT_MIN_SAMPLES the bounce-rate estimate is too noisy to trust -> always BATCH (0),
        // even if every observation so far bounced (a 3/3 high rate on 3 samples must not flip to optimistic).
        assertEquals(0, MergeOrchestrator.conflictThreshold(landed = 0, bounced = 0, optimisticThr = 16))
        assertEquals(0, MergeOrchestrator.conflictThreshold(landed = 2, bounced = 3, optimisticThr = 16))
        assertEquals(0, MergeOrchestrator.conflictThreshold(landed = 0, bounced = 19, optimisticThr = 16),
            "19 samples (<20) stays batch regardless of rate")
    }

    @Test
    fun conflictThreshold_lowConflictRoutesBatch() {
        // Warmed up (>=20 samples) and bounce fraction below HIGH_CONFLICT_RATE (0.35) -> BATCH (0):
        // the low-conflict regime where amortizing clean PRs per gate wins.
        assertEquals(0, MergeOrchestrator.conflictThreshold(landed = 100, bounced = 0, optimisticThr = 16))
        assertEquals(0, MergeOrchestrator.conflictThreshold(landed = 70, bounced = 30, optimisticThr = 16),
            "0.30 bounce (<0.35) stays batch")
    }

    @Test
    fun conflictThreshold_highConflictRoutesOptimistic() {
        // At/above HIGH_CONFLICT_RATE the batch path de-amortizes (cascade bounces) -> OPTIMISTIC (optimisticThr):
        // independent lands dodge the within-batch conflict cascade.
        assertEquals(16, MergeOrchestrator.conflictThreshold(landed = 65, bounced = 35, optimisticThr = 16),
            "exactly 0.35 is the high-conflict boundary -> optimistic")
        assertEquals(32, MergeOrchestrator.conflictThreshold(landed = 20, bounced = 80, optimisticThr = 32),
            "high conflict honors the caller's optimistic threshold")
    }

    // ---- H7': windowed (EWMA) conflict threshold (pure) ----

    @Test
    fun conflictThresholdWindowed_warmupThenTracksRecentRate() {
        // Warmup: below CONFLICT_MIN_SAMPLES -> batch regardless of rate.
        assertEquals(0, MergeOrchestrator.conflictThresholdWindowed(recentRate = 0.9, totalSamples = 5, optimisticThr = 16))
        // Warmed: recent rate is the discriminator (not lifetime). High recent rate -> optimistic even if a long
        // low-conflict history would have diluted a cumulative average below threshold.
        assertEquals(16, MergeOrchestrator.conflictThresholdWindowed(recentRate = 0.53, totalSamples = 800, optimisticThr = 16),
            "recent 53% conflict -> flip to optimistic")
        assertEquals(0, MergeOrchestrator.conflictThresholdWindowed(recentRate = 0.20, totalSamples = 800, optimisticThr = 16),
            "recent 20% conflict -> stay batch")
        // Boundary: exactly HIGH_CONFLICT_RATE flips.
        assertEquals(16, MergeOrchestrator.conflictThresholdWindowed(recentRate = 0.35, totalSamples = 100, optimisticThr = 16))
    }

    @Test
    fun conflictThresholdWindowed_customCrossoverThreshold() {
        // The crossover is tunable (runtime ARXAEL_HIGH_CONFLICT_RATE / future gate-cost-adaptive). A recent rate of
        // 0.30 stays batch at the default 0.35 crossover but flips at a lowered 0.25 crossover — the lever we need
        // when conflict parks near 0.35, so the default never commits.
        assertEquals(0, MergeOrchestrator.conflictThresholdWindowed(0.30, 800, 16, highConflictRate = 0.35))
        assertEquals(16, MergeOrchestrator.conflictThresholdWindowed(0.30, 800, 16, highConflictRate = 0.25))
    }

    @Test
    fun conflictThresholdWindowed_decisiveWhereCumulativeLagged() {
        // The cumulative-lag failure, distilled: a run whose conflict BUILT high but whose LIFETIME ratio stayed low
        // (dragged by the low-conflict early phase). Cumulative stays batch (wrong); windowed flips (right).
        assertEquals(0, MergeOrchestrator.conflictThreshold(landed = 690, bounced = 310, optimisticThr = 16),
            "cumulative 31% -> batch (the lagging miss)")
        assertEquals(16, MergeOrchestrator.conflictThresholdWindowed(recentRate = 0.53, totalSamples = 1000, optimisticThr = 16),
            "windowed reads the CURRENT 53% -> optimistic (the fix)")
    }

    // ---- H8: conflict-aware batch composition (pure) ----

    private fun pr(branch: String) = PullRequest(branch = branch, module = null, agentId = null)

    @Test
    fun composeDisjoint_allDisjointFillsBatchUpToCap() {
        // Low-conflict: every PR touches a different file -> all selected (up to cap), nothing deferred.
        val cands = listOf(pr("a"), pr("b"), pr("c"))
        val files = mapOf("a" to setOf("F1"), "b" to setOf("F2"), "c" to setOf("F3"))
        val (sel, def) = MergeOrchestrator.composeDisjointBatch(cands, cap = 8) { files[it.branch]!! }
        assertEquals(listOf("a", "b", "c"), sel.map { it.branch })
        assertTrue(def.isEmpty())
    }

    @Test
    fun composeDisjoint_defersProvenOverlapKeepingFifoHead() {
        // High-conflict: a,b,c all touch F0; d touches F9. FIFO head 'a' claims F0 -> b,c defer (proven overlap);
        // d is disjoint -> selected. Deferred list preserves order (b before c) for fair next-round retry.
        val cands = listOf(pr("a"), pr("b"), pr("c"), pr("d"))
        val files = mapOf("a" to setOf("F0"), "b" to setOf("F0"), "c" to setOf("F0"), "d" to setOf("F9"))
        val (sel, def) = MergeOrchestrator.composeDisjointBatch(cands, cap = 8) { files[it.branch]!! }
        assertEquals(listOf("a", "d"), sel.map { it.branch }, "head + the disjoint PR land; the overlappers wait")
        assertEquals(listOf("b", "c"), def.map { it.branch }, "deferred keeps FIFO order")
    }

    @Test
    fun composeDisjoint_emptyFilesNeverDefers() {
        // No overlap evidence (change-awareness off / undiscoverable diff) must never be deferred -> never worse
        // than FIFO. Two empty-set PRs both select even though set-equal (empty ∩ empty has no *claimed* path).
        val cands = listOf(pr("a"), pr("b"))
        val (sel, def) = MergeOrchestrator.composeDisjointBatch(cands, cap = 8) { emptySet() }
        assertEquals(listOf("a", "b"), sel.map { it.branch })
        assertTrue(def.isEmpty())
    }

    @Test
    fun composeDisjoint_respectsCapAndDefersOverflow() {
        // Cap binds before disjointness: 3 disjoint PRs, cap 2 -> first two selected, third deferred.
        val cands = listOf(pr("a"), pr("b"), pr("c"))
        val files = mapOf("a" to setOf("F1"), "b" to setOf("F2"), "c" to setOf("F3"))
        val (sel, def) = MergeOrchestrator.composeDisjointBatch(cands, cap = 2) { files[it.branch]!! }
        assertEquals(listOf("a", "b"), sel.map { it.branch })
        assertEquals(listOf("c"), def.map { it.branch })
    }

    @Test
    fun composeDisjoint_partialOverlapAcrossMultipleFilesDefers() {
        // Overlap on ANY shared path defers: b shares F2 with a (which claimed F1,F2) -> deferred; c is clean.
        val cands = listOf(pr("a"), pr("b"), pr("c"))
        val files = mapOf("a" to setOf("F1", "F2"), "b" to setOf("F2", "F3"), "c" to setOf("F4", "F5"))
        val (sel, def) = MergeOrchestrator.composeDisjointBatch(cands, cap = 8) { files[it.branch]!! }
        assertEquals(listOf("a", "c"), sel.map { it.branch })
        assertEquals(listOf("b"), def.map { it.branch })
    }

    /** A worktree checked out to main for a final full-gate assertion. */
    private fun integForVerify(): Path {
        val v = tmp.resolve("finalcheck")
        if (!Files.exists(v)) GitOps.git(bare, "worktree", "add", "-q", "--detach", v.toString(), "main")
        else { GitOps.git(v, "checkout", "-q", "--detach", "main"); GitOps.git(v, "reset", "-q", "--hard", "main") }
        return v
    }
}
