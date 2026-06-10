# LIMITATIONS — known limits & threat model (honest)

What this system does **not** do, and where its guarantees stop. This is deliberately documented, not
buried — the design rests on a few load-bearing assumptions, and a reader deserves to see them plainly
before trusting `main` to it. For the *why it works at all*, see [ARCHITECTURE.md](ARCHITECTURE.md).
The one assumption that pervades everything: **all local agents are trusted**
(see #4).

---

## 1. Optimistic-merge soundness depends on a complete dependency graph

The auto-route fast path (optimistic land + module-scoped async gate) is sound only if the affected-module
closure it computes is a true *superset* of a change's real blast radius. That closure is built from
`ModuleGraphProbe`, which discovers **only declared Gradle `ProjectDependency` edges** — it walks each
subproject's configurations and records inter-project dependencies. It does **not** see runtime/reflection
coupling, resource or classpath-scanning coupling, service-loader wiring, or cross-module test dependencies
that aren't expressed as a Gradle project dependency. A change whose true dependents exceed its discovered
closure can be routed OPTIMISTIC, landed, and its async gate will test too few modules — so a real
regression in an undiscovered dependent can slip onto `main` and not be caught by that PR's gate.

- **Severity:** Low–Medium (tightened). The fast path now requires **positive knowledge** (see below), so
  the only residual is *truly-undeclared* coupling (reflection/resources/service-loader/cross-module tests
  with **no** Gradle edge at all) between modules the graph *does* know about. Still bounded by branch-gating
  and by the next PR's gate re-testing the module.
- **Mitigation (in place — hardened):** `ModuleGraphProbe` discovers from the build itself (all configs'
  `ProjectDependency` edges) **and now the full subproject set**. The router is **fail-safe**: it routes a PR
  OPTIMISTIC only when the module is a *known* node AND its closure is small; an unknown module — or a
  wholly-failed discovery (empty set) — routes **BATCHED** (the sound full gate), so a missing/incomplete
  graph can no longer silently send a change down the narrow-gate fast path. The batched path never lands
  red; auto-route already sends large-closure PRs there; lowering `MergeRouter.threshold` widens the sound
  set further (threshold 1 ⇒ only truly-independent modules take the fast path).
- **Residual now empirically covered (verify-then-trust):** a module's first `ARXAEL_OPTIMISTIC_CONFIRMATIONS`
  (default 2) optimistic changes are gated against the **FULL project**, which runs *every* module's tests and
  so catches a break in any module — declared dependent or not (reflection/resource/runtime coupling
  included). Only after that many clean full passes is the module's narrow declared closure trusted for speed
  (the count is persisted, so the learning survives restarts). This shrinks the residual to: undeclared
  coupling that does **not** manifest in a module's first N changes but appears later — a much narrower window
  than "from the first change." Set the confirmations higher for stricter projects, or `0` to trust the graph
  immediately.
- **What would fully close even that:** a coupling source independent of both the declared graph and sampling
  — e.g. continuous test-affected-by analysis from the build's task graph, or runtime classpath tracing.

## 2. Single box, single process — no HA, no horizontal scale

The daemon is one long-lived process on one machine. `PrJournal` makes a **crash recoverable** (it
re-enqueues submitted-but-unfinished PRs on restart, which also re-gates any optimistically-landed-but-
unverified PR), but there is **no failover and no replication**: while the process is down, nothing lands;
if the box dies, the service is gone until it's brought back. Capacity is **per-box** — density scales with
the cores/RAM of the one machine, not by adding machines. There is no sharding of the queue or the executor
across hosts.

- **Severity:** Medium — it's a real ceiling, but largely by design.
- **Mitigation (in place):** crash-durable queue + re-gate-on-recovery (a crash cannot leave an unverified
  change silently on `main`; validated live by `bench/chaos_recovery.py`); bounded/fail-closed executor so
  the one box degrades gracefully rather than wedging. **Supervised self-healing** (`scripts/install-service.sh`):
  installs a systemd unit (`Restart=always`) + a 30s liveness timer, so a **crash** *or* a **wedge** auto-
  restarts the daemon — and because restart triggers `PrJournal` recovery, the service heals to a sound `main`
  with no manual step. This is right-sized HA for the single-box thesis: same box, now self-healing (downtime
  = restart seconds, not until-someone-notices).
- **What would close it further:** a standby/failover process on a SECOND machine and/or sharding projects
  across boxes — a deliberate departure from the "one warm box you own" thesis, not a bug. Today, with the
  supervised service, single-box availability with fast self-heal is the SLO.

## 3. Multi-language: adapters ship, but the resource model is JVM-tuned

The `BuildAdapter` SPI is the substrate's one extension point and is genuinely generic (executor and
`/invoke` surface don't know about Gradle). **Multiple adapters now ship:** `gradle` (warm Tooling-API),
the generic `exec` (any command), and named per-language adapters — `maven`, `pytest`, `cargo`, `go`,
`vitest`, `npm`, `make` (`CommandAdapter`, each with a zero-config default test command). An agent sends
`{"adapter":"pytest","worktree":"…"}` and it runs on the same bounded executor + merge orchestration.
The remaining caveat is **tuning, not capability**: the resource model that makes density work — per-build
footprint (≈1.5–4 GB seed), worker-per-build scaling, the IO-never-binds finding, the per-worktree-home
cache-lock fix — is **calibrated on JVM/Gradle builds**. Those numbers are not yet validated for other
toolchains, and the non-JVM adapters are process-per-invocation (no warm daemon to amortize), so their
density win is the bounded-concurrency gate + the merge workflow, not a warm connection.

- **Severity:** Low — the adapters exist and run; what's unproven is whether the *footprint/worker* tuning
  is optimal for, say, a heavy pytest suite.
- **Mitigation (in place):** the named adapters + `exec` cover the common ecosystems out of the box; the
  governor learns footprint at runtime (EWMA), so the bound self-corrects to a new toolchain's real
  footprint even if the 1.5 GB seed is wrong for it.
- **What would close it:** a tuning pass per ecosystem (footprint seed + worker count) on a real non-JVM
  suite, and — where the toolchain supports it — a warm-process variant of the adapter.
- **Toolchain-home contention (low severity; ambient home by design):** CommandAdapters run the toolchain
  with its AMBIENT home (`~/.m2`, `~/.gradle`, …), which could contend on that home's cache lock at *high
  concurrent* `/invoke`. It does NOT affect the **merge gate** (non-gradle PRs route batched → the gate runs
  one-at-a-time). A per-worktree `GRADLE_USER_HOME` for `gradlew` was tried and **reverted**: the wrapper's
  Gradle *distribution* lives in `GRADLE_USER_HOME/wrapper/dists` and `GRADLE_RO_DEP_CACHE` shares only deps,
  so per-worktree homes re-download the whole distribution per worktree — worse than the lock. The correct
  fix (per-worktree caches but a SHARED `wrapper/dists` symlink) is deferred until a real high-concurrency
  `gradlew`-`/invoke` workload shows the ambient home actually binds.

## 4. Trust model — loopback + trusted agents + an arg allowlist, and that's all

The only guards are: the daemon binds **loopback only** (`127.0.0.1`), every agent on the box is **trusted**,
and `ArgAllowlist` fail-closed-validates caller-supplied build args (so a caller can't pass
`--gradle-user-home` / `--init-script` / `--project-cache-dir` and subvert the substrate's own isolation).
That is the entire security boundary. Critically:

- **Builds execute arbitrary code by their nature.** Running a project's tests runs the project's code.
  The arg allowlist constrains *flags*, not what a test does.
- **There is no isolation *between* agents.** Worktree servers separate caches to avoid lock contention,
  not for security. A buggy or hostile agent shares the same box, the same executor, the same `main`,
  and the same git repo as everyone else — its blast radius is real (it can submit branches, consume the
  whole bound, or land changes).
- **There is no auth and no tenancy.** Any local process that can reach the loopback port is an agent.
  There is no per-agent identity check, quota, or access control on the merge surface.

- **Severity:** High **if** the trust assumption is violated. Within the intended model (your own box, your
  own agents) it's acceptable; outside it, it is not a multi-tenant or untrusted-workload system.
- **Mitigation (in place):** loopback binding (no network exposure), fail-closed arg allowlist, bounded
  executor (one agent can't unbound-ly exhaust the box — it queues / gets `OVERLOADED`).
- **What would close it:** per-agent auth + quotas, and real build sandboxing/isolation (containers, user
  namespaces, seccomp) between agents — explicitly out of scope today. **Do not expose the port beyond
  loopback and do not run untrusted agents against it.**

## 5. Validation is on a synthetic fixture at small scale

The performance and soundness results in [ARCHITECTURE.md](ARCHITECTURE.md) are real and re-tested, but they were
produced on a **synthetic setup**: ~6–24 simulated agents, ~75 s windows, a **12-module toy fixture**,
seconds-long builds (`NoopAdapter` / small Gradle fixture), and **one box**. The phase-5 wide-DAG run is
the most realistic point and it holds — but it is still a generated fixture. None of this proves the story
on a **large, real, long-history monorepo** with minute-long builds, hundreds of modules, deep/irregular
dependency graphs, and many agents sustained over hours.

- **Severity:** Medium — the *mechanisms* are validated; the *scale-up* is extrapolated.
- **Mitigation (in place):** the design's soundness rests on branch-gating + the batched fallback, which
  don't depend on scale; the adaptive governor is built precisely to self-correct as builds grow heavier
  (the answer to "what happens as the project grows"). Every striking result was re-tested before being
  trusted (see ARCHITECTURE.md). **Now also validated in an ISOLATED CONTAINER**
  (`bench/realworld_container.sh`): a clean `gradle:8.10.2-jdk21` container with the kit **built from source
  inside it**, a real 8-module project (240 classes, real JUnit5/pitest/JaCoCo deps), 8 agents landing PRs on
  **fresh per-worktree gradle homes doing real Maven Central downloads** — 29 landed, 13.7 merges/min, **0
  reverts, 0 errors, 0 Maven 429s**, main never broke. So it's no longer only "this pre-warmed box": the
  shipped defaults survive a from-scratch build + real cold downloads + real concurrency in isolation.
  **And against a REAL OSS project** (`bench/realworld_oss.sh`): 16 agents adding real compiling tests to
  **google/gson** in an isolated container, each PR gated by gson's OWN Maven suite (`mvn -q -pl gson -am
  test`, ProGuard + full Surefire real build) through the real MergeOrchestrator. Result (16 closed-loop
  agents, fully drained): **48/48 good PRs landed, 2/2 bad PRs caught, 0 reverts, 0 daemon errors, 0 client
  errors, main green**; 7.0 merges/min over the full run (~14/min warm), p50 time-to-land 46.7s —
  **gate-bound by gson's real build time** (throughput = substrate capacity ÷ real build time; the density
  *win* shows on the gradle cache-lock A/B, not here, since Maven uses a shared ~/.m2). The run also surfaced
  + fixed two real issues (batched path didn't expose backlog → added `queueDepth`; unattributable red
  batches re-gated O(n) → now bisect O(k·log n), so all good PRs landed despite bad ones mixed in). So
  soundness + the full workflow are now proven on a real project with a real test suite, not only fixtures.
- **What's still open:** the real-project run touches gson's core module with trivial added tests (not deep
  edits across a long-history monorepo), the container shares the host's kernel/CPU (not a separate machine),
  and runs are minutes not hours; ~5% of agent submits hit client-side errors under a tight 16-thread loop.
- **What would fully close it:** a run against a genuine large monorepo with real (minute-long) build times
  and a real agent fleet over hours — measuring sustained density, land rate, and revert behavior under real
  closure distributions.

---

These limitations are documented on purpose. The project culture values honest correction over polish — if
one of these bites you, that's a known edge, not a surprise.
