# Dogfood feedback — Arxael driving `trading-system-next`

`trading-system-next` (institutional Kotlin/JVM trading system) has been built almost entirely
through Arxael as its CI/merge substrate over a multi-month arc. **Our usage is NON-NATIVE in
places** and we call that out below — but even the non-native stress surfaced real, citable
product gaps (including one CRITICAL soundness bug). All file:line citations are against
`~/arxael` at the commit this was written (`4ec3aaa` + uncommitted config-cache diff).

## Our setup (non-native — read first)

We run Arxael in a **foreman + many parallel worker-agents** pattern ("manager-by-default"):
one coordinator dispatches N tightly-scoped worker agents. Each worker creates its OWN hub
worktree off `~/.arxael/hubs/trading-system-next.git` via `git worktree add -b <branch> <path> main`,
builds via `POST /invoke`, and lands via `POST /merge/submit` + `GET /merge/pr?branch=…&wait=N`.
Many concurrent agents share the one warm executor.

This is **heavier concurrency and far more worktree churn** than the single-developer native
flow Arxael's docs assume (AGENTS.md walks one agent through one worktree). The native flow is
well-defended; the gaps below are mostly where multi-agent scripting against the hub finds edges
the single-agent path never touches.

---

## 1. Phantom-land: a refused ref-update reported as `landed` (CRITICAL soundness)

**What happened.** A `/merge/submit` returned, via `GET /merge/pr`,
`{"state":"landed","terminal":true,"commit":"<sha>"}` and the `landed` counter incremented —
but `refs/heads/main` never moved. The merge commit was reachable from NO ref (orphaned). A
worker that polled `/merge/pr` to its terminal `landed` state believed its work had shipped; it
had not. In a multi-agent fan-out this is silent data loss of completed work, and worse, the
counters/metrics report success — there is no signal anything is wrong.

**Root cause (cited).** `GitOps.setBranch` (`core/.../merge/GitOps.kt:66-67`) is
`ok(bare, "branch", "-f", branch, commit)` — it returns a success `Boolean`. All three
ref-advance sites in `MergeOrchestrator.kt` **DISCARD that Boolean**:
- `:493` optimistic land — `GitOps.setBranch(bare, "main", head)` (return ignored)
- `:880` `landBatch` — `GitOps.setBranch(bare, "main", head)` (return ignored)
- `:719` `revertFromMain` — `if (r.ok) { GitOps.setBranch(bare, "main", GitOps.rev(wt, "HEAD")); true }`

When `main` was checked out in another (linked) worktree, `git branch -f main` exits 128
("cannot force update the branch 'main' checked out at …") → `setBranch` returns `false` → the
`false` is swallowed → the orchestrator proceeds to `setPr(pr.branch, "landed", true, …)`
(`MergeOrchestrator.kt:546` and siblings) and `mLanded.incrementAndGet()` (`:502`). The PR
outcome and the `landed` aggregate (`:981`) both lie.

**What we did locally.** Two operator-side guardrails (see finding #4) so the precondition that
triggers exit-128 can never be created by our agents. That prevents the trigger; it does NOT fix
the soundness hole — a refused `branch -f` from any cause would still phantom-land.

**Product suggestion.**
- **Check the Boolean at all three sites.** On `false`, emit a distinct event (e.g.
  `merge_land_failed`) and drive the PR to `error` / requeue — **never** `landed`. A land that
  didn't move the ref is not a land. This is the top ask.
- **Defense in depth:** after `setBranch`, assert `GitOps.rev(bare, "main") == head` before
  recording `landed`. (`landBatch` at `:875` already reads `rev(bare,"main")` for staleness —
  the same read post-set closes the loop.)
- **Detect the precondition:** at `/merge/register` (and/or `/health`), warn if `main` is
  checked out in a linked worktree of the hub — that state guarantees every land will be refused.
  No such check exists today (no "checked out" string in `core/.../merge/*.kt`).

---

## 2. Scoped `--tests <Class>` rejected by the Tooling API

**What happened.** Driving a scoped/changed-files test (`--tests <FQCN>` to run a single test
class instead of the full suite — important for perf at our fan-out scale) failed the build with
`Unknown command-line option '--tests'`. Surfaced in `/health` `recentErrors` (the
`recentErrors` ring at `EventLog.kt:55,87`, projected at `InvokeServer.kt:120-122`) — 3 hits.

**Root cause (cited).** `GradleAdapter.kt:116-119` sets tasks and args on SEPARATE Tooling-API
vectors:
```
.apply { if (spec.tasks.isNotEmpty()) forTasks(*spec.tasks.toTypedArray()) }   // :117
.withArguments(spec.args + injected)                                            // :119
```
`--tests` is a **task option of the `test` task**, not a global Gradle arg. When `test` arrives
via `forTasks(...)` and `--tests <Class>` arrives via `withArguments(...)`, the option is unbound
— the Tooling API can't associate it with a task it didn't receive in the same vector — so it
rejects it as an unknown command-line option.

**What we did locally.** Avoided per-class `--tests` scoping through the adapter; relied on
Arxael's own module-graph routing for scope instead. Workable, but coarser than class-level
selection.

**Product suggestion.** Fold the tasks into the argument vector so task options bind:
`withArguments(spec.tasks + spec.args + injected)` (dropping/relaxing `forTasks`). The Tooling
API treats the leading non-flag tokens as the task list, and `--tests` then binds to the
preceding `test` task exactly as it does on the CLI. This unlocks the scoped-test path.

---

## 3. The worktree-on-`main` footgun (our mistake — but the product can defend)

**What happened.** Our coordinator once ran `git -C <hub> worktree add <path> main` — **omitting
`-b`** — which checks out the `main` BRANCH into a linked worktree and LOCKS `refs/heads/main`.
That is precisely the precondition that triggered finding #1: every subsequent `branch -f main`
exited 128 and phantom-landed.

**Root cause.** Operator/agent scripting error, not an Arxael defect. NATIVE Arxael never does
this — and AGENTS.md:46-48 already shows the correct form
(`git -C <hub> worktree add /abs/checkout -b my-feature main`). But any agent scripting against
the hub directly (as our fan-out pattern does) can make this mistake, and nothing stops them or
explains the downstream consequence.

**What we did locally.** A PreToolUse(Bash) hook that blocks the dangerous form (see #4).

**Product suggestion.**
- A **loud warning in AGENTS.md** next to the worktree-add guidance: "do not check out the
  default branch itself — use `-b <branch>` (or `--detach`); checking out `main` locks the ref
  and Arxael can no longer land."
- The **register/health worktree-checkout warning from #1** — surfacing the bad state is the
  product-native version of our hook.

---

## 4. Operator-side guardrails we had to add — the heart of this feedback

Our multi-agent pattern forced us to add two PreToolUse hooks at the agent harness level
(wired in `trading-system-next/.claude/settings.local.json:25-45`). Each exists to prevent a
footgun Arxael surfaces only after the damage is done.

**`~/.claude/hooks/block-main-checkout-edit.sh`** (PreToolUse Edit|Write). Our worker agents
share the project's primary checkout at `/home/azureuser/trading-system-next`, which sits ON
`main`. An agent that edits files there is editing a checkout whose changes **do not land via
Arxael** (Arxael lands hub branches; the primary checkout's working tree is never submitted) —
so the work silently never ships. The hook denies any Edit/Write under the main checkout
(except the gitignored `settings.local.json`) and tells the agent to work in its hub worktree
instead (`block-main-checkout-edit.sh:11-14`).

**`~/.claude/hooks/block-worktree-add-main.sh`** (PreToolUse Bash). Born directly from finding
#3. It rejects `git worktree add … main` (or `master`) that checks out the default branch as the
trailing commit-ish without `-b`/`-B`/`--detach` (`block-worktree-add-main.sh:20-29`), with an
error explaining that it locks `refs/heads/main` and causes silent "landed-but-not-published"
merges.

**The product lesson.** Both hooks compensate for the same class of gap: **an action that looks
successful but won't actually land, with no error at the time it's taken.** In each case the
agent gets no signal from Arxael — the signal arrives (if at all) as a swallowed `false`
(finding #1) or never. Multi-agent usage needs footgun guardrails the product could surface
itself:
- "you're editing/operating on a checkout that won't land" detection at the merge surface;
- the worktree-on-`main` register/health warning;
- and above all, the finding-#1 fix so a refused land is an *error*, not a phantom success.

We were able to add these as harness hooks because we control the agent harness. A user driving
Arxael from a different orchestrator can't, and would just lose work silently.

---

## 5. Warm-cache × configuration-cache interaction (fix in hand — candidate for upstream)

**What happened.** When a registered project sets `org.gradle.configuration-cache=true` (ours
does), Arxael's module-graph probe and dep-cache warmer broke on the **2nd+** invocation against
the same project dir: the probe parsed to ZERO modules, so everything silently routed BATCHED —
a silent loss of incremental/scoped routing (exactly the perf path that matters at our scale).

**Root cause (cited).** Both `ModuleGraphProbe` and `DepCacheWarmer` run a `help` build whose
ONLY purpose is the init-script's side effects (the probe's `ARXMODDIR`/`ARXDEP` `println`s; the
warmer's dependency `resolve()`). With config cache on, Gradle stores the config cache in the
PROJECT dir (`.gradle/configuration-cache`) and on the 2nd+ run REUSES it, **skipping
`projectsEvaluated`** — so the init-script callbacks never fire: no tagged lines printed (graph
→ 0 modules), no resolve side effects. See the comment + fix at
`ModuleGraphProbe.kt:72-80` and `DepCacheWarmer.kt:52-59`.

**What we did locally / the fix.** Add `--no-configuration-cache` to the probe and warm builds
ONLY (`withArguments("--init-script", init.toString(), "-q", "--no-configuration-cache")` at
`ModuleGraphProbe.kt:80` and `DepCacheWarmer.kt:59`). Real gate builds keep config cache — the
flag is probe/warm-only. This is currently an **uncommitted local diff** in `~/arxael`.

**Product suggestion.** Upstream this. Any registered project with config cache enabled
(increasingly the Gradle default-recommended posture) hits the same silent routing degradation.
The probe/warm builds should always disable config cache since they exist purely for
projectsEvaluated/resolve side effects.

---

## 6. Stale deployed dist vs the warm executor (environmental — noted briefly)

**What happened.** The project's deployed daemon dist at `/opt/trading-daemon` was stale
(owned by a since-removed `test-orchestrator` user, last touched 2026-06-14) relative to what
Arxael's warm executor actually builds from the hub.

**Root cause.** Orthogonal to Arxael — a deploy/provenance issue on our side. But it's a general
hazard: a CLI/daemon running off a *deployed* dist can silently diverge from what Arxael builds
and lands, so "Arxael is green" and "the running binary has the fix" are not the same statement.

**Product suggestion (light).** A short docs note on build-vs-deploy provenance — Arxael lands
hub commits; it does not deploy, and a deployed dist is only as fresh as its last deploy step.
Helps users avoid debugging a "fixed" behavior against a stale binary.

---

## Top product asks (prioritized)

1. **(CRITICAL) Fix the phantom-land.** Check the `GitOps.setBranch` Boolean at all three
   sites (`MergeOrchestrator.kt:493,719,880`); on `false` emit `merge_land_failed` and go to
   `error`/requeue — never `landed`. Add a post-set `rev(bare,"main")==head` assertion. (#1)
2. **Warn when `main` is checked out in a linked hub worktree** at `/merge/register` and
   `/health` — the state that guarantees every land is refused. (#1, #3)
3. **Bind `--tests` (and other task options):** fold tasks into the arg vector,
   `withArguments(spec.tasks + spec.args + injected)`. Unlocks scoped/changed-files tests. (#2)
4. **Upstream `--no-configuration-cache` on the probe/warm builds** so config-cache projects
   don't silently lose incremental routing. (#5)
5. **AGENTS.md footgun warning** on `worktree add … main` (use `-b`/`--detach`), plus a
   "checkout that won't land" note for agents scripting against the hub. (#3, #4)
6. **Build-vs-deploy provenance note** in the docs. (#6)
