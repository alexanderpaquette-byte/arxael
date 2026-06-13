# ARCHITECTURE — how everything works (current, as-built)

This is the authoritative reference for the **shipped system** as it stands now. (For *why* the design is
what it is — the experiments, dead-ends, and numbers. For a gentle visual map, see [OVERVIEW.md](OVERVIEW.md). To just run it,
[../QUICKSTART.md](../QUICKSTART.md) / [../AGENTS.md](../AGENTS.md).) For what this system deliberately does
**not** do — and the trust model it assumes — see [LIMITATIONS.md](LIMITATIONS.md).

## The one-paragraph model
A single long-lived daemon (`dev.arxael.Main`) holds a pool of **warm, reused build servers** behind one
loopback HTTP surface. Many trusted local agents route their builds/tests through it instead of each
cold-starting their own — a global **bounded** semaphore keeps in-flight work to what the box can hold
(density, not single-build speed). On top of that executor, a **merge orchestrator** lets those agents land
PRs onto one shared `main` fast and without conflicts. An **adaptive governor** continuously sizes the bound
to the machine and the workload. Everything auto-configures, self-cleans, and reports itself.

## Component map (every source file, one line)
```
Main.kt                         wires + starts everything; shutdown hook closes it all in order
config/
  BoxConfig.kt                  reads the box (cores, RAM) + ARXAEL_* env -> the resolved config & bounds (pure computeBounds)
  FootprintStore.kt             persists the governor's learned per-build memory footprint across restarts
protocol/Protocol.kt           the wire types: InvokeSpec/InvokeOutcome, MergeRegisterSpec/MergeSubmitSpec
invoke/
  InvokeServer.kt               the loopback HTTP surface (all routes); self-describing GET /
  ArgAllowlist.kt               fail-closed allowlist for caller-supplied build args
executor/
  WarmExecutor.kt               THE core: bounded permits (+ reserved high lane), warm WorktreeServer pool, dispatch
  WorktreeServer.kt             one warm per-worktree build session, serialized; one per project worktree
  AdjustableSemaphore.kt        permit gate the governor resizes live (graceful shrink)
  DurationTracker.kt            EWMA of build times -> adaptive overload timeout
  Watchdog.kt                   probe-only liveness watch (report/fail-closed, never reconnect-in-place)
  GradleDaemonReaper.kt         on shutdown, kills THIS daemon's build daemons immediately (scoped via /proc)
adapter/
  BuildAdapter.kt               the SPI: open(worktree)->session; one warm session driven one invoke at a time
  gradle/GradleAdapter.kt       Gradle Tooling-API impl (warm connection, per-worktree caches, RO dep cache, live workers)
  NoopAdapter.kt                deterministic no-toolchain adapter for tests/benchmarks (sleepMs= synthetic load)
  AdapterRegistry.kt            name -> adapter
autosize/
  AdaptiveSizer.kt              PURE controller: nextTarget (AIMD on mem/cpu/io pressure + demand, hard caps), workersFor, footprint EWMA
  AdaptiveGovernor.kt           samples /proc, learns footprint, drives executor.setConcurrencyTarget + live workers
merge/
  MergeService.kt               project lifecycle: attach worktrees, discover graph, warm dep cache, run/teardown orchestrator
  MergeOrchestrator.kt          the queue: load-adaptive routing -> optimistic land + async gate / batched gate-then-land; durable
  MergeRouter.kt                routes each PR by live load (gate-fill + hysteresis + batchCap-awareness), bounded by dependency-closure size; generic over module id
  MergeGate.kt                  the gate SPI (test a worktree, optionally module-scoped) + PullRequest/GateResult
  ExecutorMergeGate.kt          production gate: runs tests on the WarmExecutor (reserved high lane) + CulpritAttribution
  CulpritAttribution.kt         pure: parse a build's failed modules -> name the culprit PR
  GitOps.kt                     thin git-CLI wrapper (the only IO dependency of the orchestrator)
  ModuleGraphProbe.kt           auto-discovers the Gradle module dep graph (so register needs no hand-authored deps)
  DepCacheWarmer.kt             warms a shared read-only dep cache so per-worktree homes don't re-download (no 429)
  PrJournal.kt                  durability: journal PR lifecycle -> re-enqueue unfinished PRs after a crash
eventlog/EventLog.kt           append-only JSONL audit trail + bounded recent-errors ring for /health
```

## Key flows

### 1. Startup & auto-configuration (no manual config)
`Main` builds `BoxConfig.fromEnv()`: it reads **cores** (`Runtime.availableProcessors`) and **RAM**
(`/proc/meminfo`), then `computeBounds` derives `maxConcurrent = min(coreBound, memBound)` where
`coreBound = cores·agentsPerCore·budget%` and `memBound = (usableRAM−headroom)·budget% / perBuildFootprint`.
The per-build footprint seed is the **learned** value from `FootprintStore` if a prior run saved one, else
1.5 GB. It then starts: the `WarmExecutor`, the `Watchdog`, the `AdaptiveGovernor`, the `MergeService`, and
the `InvokeServer`. A JVM shutdown hook stops them in order and **immediately reaps the build daemons**
(`GradleDaemonReaper`) instead of waiting out their idle-timeout.

### 2. An agent build (`POST /invoke`)
`InvokeServer` → `ArgAllowlist.check` (fail-closed) → `WarmExecutor.submit`:
acquire a normal-lane permit then a global permit (or fail closed `OVERLOADED` past the adaptive timeout) →
get/create the warm `WorktreeServer` for that worktree path (keyed by path; LRU-evicted past `warmServers`) →
`GradleAdapter.run` on its warm Tooling-API connection with isolation + scale-to-cores args → stream output,
return `SUCCESS|FAILED|OVERLOADED|ERROR`. The connection is **never closed per-invoke** (warm reuse is the
whole point); build duration feeds `DurationTracker`.

### 3. The merge workflow (`/merge/register` → `/merge/submit`)
`register` (usually done by `arxael up`): attach an integration worktree + a pool of gate worktrees to the
bare repo; **auto-discover** the module graph (`ModuleGraphProbe`) unless given; if per-worktree homes are
on, **warm** the shared RO dep cache (`DepCacheWarmer`); start the `MergeOrchestrator` and `recover()` any
journaled-but-unfinished PRs. The cheap fatal check (repo has `main`) is synchronous, but the cold-Gradle
parts (the module-graph probe + dep-cache warm) run **in the background**: `register` returns `202` at once
and exposes `registerState` (`registering` → `ready` | `failed`) on `/merge/status`, so a cold first register
can never block a client past its timeout. Then agents `submit` branch-tested PRs. The orchestrator's loop drains the
queue and, per PR, `MergeRouter` chooses **load-adaptively** (the default `balanced` mode), bounded by the
PR's dependency-closure:
- **OPTIMISTIC** when the gate pool has room to fill — pending work (`queueDepth + inFlightGates`) is filling
  the async gate pool — or the closure is small: merge onto `main` and land **immediately**, then verify async
  on a gate worktree testing **only that PR's module** (`module-scoped` → a bad PR can poison at most its own
  gate, never others' → no cascade). A red gate **auto-reverts** the commit from `main`.
- **BATCHED gate-then-land** when load is low, or a batch would dominate the gate pool, or the closure is
  large (hub/deep chain): merge a batch onto a detached `main`, one incremental integration test, land all if
  green; on a red batch, `CulpritAttribution` bounces only the culprit PR(s) and re-tests the remainder.
  **Never lands a red merge.**
Hysteresis keeps the regime sticky (no flapping between optimistic and batched); batchCap-awareness forces
batch when batch size dominates the pool. `ARXAEL_MERGE_MODE=conservative` pins everything batched, `fast`
leans optimistic. Gate tests run via `ExecutorMergeGate` on the executor's **reserved high-priority lane** so
landings never starve behind agent branch-tests. Soundness rests on PRs arriving branch-tested green (the
agent's job).

### 4. The adaptive sizing loop (every ~3 s)
`AdaptiveGovernor.tick`: read free memory + CPU load + `%iowait` from `/proc`; learn the per-build footprint
(EWMA of used-above-idle-baseline per in-flight build; persist on drift) → `AdaptiveSizer.nextTarget`
(**AIMD**: below the memory headroom floor → multiplicative shrink; demand waiting + room + spare CPU + spare
IO → +1; thin margin → −1; never outside the hard `[floor, ceiling]` caps) → `executor.setConcurrencyTarget`
(resizes the `AdjustableSemaphore` gracefully) and derives **workers-per-build** `W≈cores/C` so total slots
track cores. The overload timeout is `observed-build-time × mult` (floored at the configured value).

### 5. Durability (crash recovery)
`PrJournal` records every PR's `SUBMIT`/`DONE` outside the worktree base. On `register`, `recover()`
re-enqueues PRs that were submitted but never finished — which both recovers lost queue entries **and**
re-gates any optimistically-landed-but-unverified PR (re-merge is a no-op; the gate re-runs), so a crash
can't leave an unverified change on `main`.

## HTTP API (loopback `127.0.0.1:<port>`)
| route | method | purpose |
|---|---|---|
| `/` | GET | self-describing API card (also the friendly fallback for unknown paths) |
| `/health` | GET | liveness, resolved config (cores/RAM/budget/binding), live target/workers, recent errors |
| `/invoke` | POST | run a build/test in a worktree on the warm executor |
| `/warmup` | POST | pre-spawn a worktree's build daemon off the critical path |
| `/merge/register` | POST | register the project (operator; usually via `arxael up`). Returns `202` immediately; the cold-Gradle probe runs async — poll `/merge/status` for `registerState=ready` |
| `/merge/submit` | POST | submit a branch-tested PR to land on `main` |
| `/merge/status` | GET | merge-queue stats (landed/reverts/bounces/errors/in-flight) + `registerState` (idle/registering/ready/failed) |
| `/shutdown` | POST | graceful stop (closes everything, reaps daemons) |

Wire shapes are in `protocol/Protocol.kt` and live on `GET /`.

## Invariants that keep it safe
- **Bounded, fail-closed.** In-flight work never exceeds `maxConcurrent`; excess **queues**, genuine overload
  returns `OVERLOADED` rather than wedging. A **reserved high lane** keeps merge-gate tests from starving.
- **Warm reuse.** Connections/daemons stay warm during activity (reuse is the value); they're killed only on
  shutdown and idle-timed-out otherwise. Worktree servers are keyed by stable path; agents must reuse a
  worktree.
- **main is never knowingly broken.** Batched never lands a red merge; optimistic lands only branch-tested
  PRs and auto-reverts a failed module-scoped gate.
- **Adapts within hard caps.** The governor only moves the bound inside `[concurrencyFloor, concurrencyCeiling]`.
- **Fail-closed isolation.** Caller args are allowlisted; per-worktree writable caches avoid the cross-process
  Gradle lock; a shared RO dep cache avoids re-download/429.

## Observability & lifecycle
Every surface emits to `<stateDir>/events.jsonl` (full audit trail). `EventLog` also keeps a bounded ring of
recent **fault** events, surfaced on `/health` (`recentErrorCount` + last few) alongside the orchestrator's
`errors` counter — so problems are visible without grepping. `arxael status`/`logs` translate these to plain
English. Cleanup is automatic: the shutdown hook closes worktree-servers, orchestrator worktrees, governor,
and log, and reaps build daemons; crash leftovers are pruned on the next register; `scripts/reap-daemons.sh`
is the manual sweep (scoped, never self-kills).

## How it's tested (and what's deliberately not unit-tested)
Three layers, matched to what each kind of code needs:
- **Pure logic → unit + mutation tests.** Routing, closure sizing, culprit attribution, the AIMD sizer,
  worker coupling, bound resolution (`computeBounds`/`fromEnv` via an injected env), the gate-result mapping,
  the journal, `/proc` parsers, the daemon-reaper scoping predicate, the watchdog scan — all have direct
  tests, and PIT mutation testing checks the tests actually *catch* a broken change (~71% of covered
  mutations killed; run `scripts/quality.sh`).
- **Orchestration + executor → real-git integration + concurrency tests.** `MergeOrchestratorTest` drives the
  queue against a real git repo with a deterministic fake gate (good PRs land, a dependent-break is reverted,
  batched attributes the culprit, crash-recovery replays, **main never left broken**); `WarmExecutorConcurrencyTest`
  exercises the bounded gate + reserved lane + live resize under threads; `MergeServiceTest` covers register→submit→land.
- **HTTP surface, `Main`, Gradle Tooling-API adapters → live smoke + load drivers.** `scripts/smoke.sh`
  and the `--executor` runs exercise these end-to-end; they're excluded from the
  mutation scope (a mutation score over loopback-I/O / process-wiring / a real toolchain is misleading), as
  is generated serializer code.
- **Deliberately accepted:** surviving mutants are predominantly removals of git/filesystem *side-effect*
  calls and timing arithmetic in the orchestrator/executor — killing them would require brittle
  intermediate-state assertions for no real safety gain; the behaviour is covered by the integration + load
  tests above. We track the number rather than chase 100%.

## Configuration
All `ARXAEL_*` env, every value box-derived by default; the budget dial (`ARXAEL_BUDGET_PCT`) and exact caps
(`ARXAEL_CORES` / `ARXAEL_USABLE_RAM_MB` / `ARXAEL_MAX_CONCURRENT`) let it share a box.
`ARXAEL_MERGE_MODE` (`conservative|balanced|fast`, default `balanced` = self-tuning) picks the merge risk
posture; the gate-fill / hysteresis / batchCap override knobs + the full table are in [SETUP.md](SETUP.md).
Full table + the auto-sizing explanation: [SETUP.md](SETUP.md).

## Where everything is documented
- **Run it:** [../QUICKSTART.md](../QUICKSTART.md) (fresh box) · [../AGENTS.md](../AGENTS.md) (agents) · `scripts/arxael` (daily)
- **Configure it:** [SETUP.md](SETUP.md) (every knob + how auto-sizing works)
- **How it works (this doc):** components, flows, API, invariants
- **Shape / visual map:** [OVERVIEW.md](OVERVIEW.md)
