# Arxael — System Overview (meta map)

> Simplified, generated map of how the pieces fit together. The authoritative reference is
> [ARCHITECTURE.md](ARCHITECTURE.md); this doc only covers the shape.
>
> 🌐 中文版本: [OVERVIEW.zh-CN.md](OVERVIEW.zh-CN.md)

**One line:** many trusted local AI agents work on **one project** through **one warm, bounded, shared
executor** on a box you own — they branch → test → open a PR → **merge to main**, fast and without
conflicts, on a box that **sizes itself**. The win is **density** (agents-per-box before collapse),
not single-build speed.

> **Color key** used in every diagram below:
> 🔵 agents/callers · 🟡 gatekeeper/control · 🟣 build service / core · 🟢 success · 🔴 failure · ⬜ config/aux · 🟦 artifacts

---

## 0. The simple version

There are three plain ideas. Everything else is detail.

### (a) One shared build service, not one per agent

**The situation:** lots of AI coding agents are working at once. Every time one makes a change, it
has to **build and test** its code to check the change is good. Building/testing is the slow, heavy
part — it needs a program that takes time to start up and uses a lot of memory.

**How everyone else does it:** give every agent its **own private machine** that starts its own
build program from cold, every time. Fine for one or two agents. But run many at once and all those
build programs start up together → the computer runs out of memory and **everything falls over**.

**What we do:** run **one shared build service** that's already started and kept ready (so no slow
cold start). All the agents send their build/test requests to it. A **gatekeeper** only allows as
many requests at once as the computer can actually handle, so it stays fast and never falls over.

> The question that matters isn't *"how fast is one build?"* (it's the same either way) —
> it's *"how many agents can share one computer before it falls over?"*

```mermaid
flowchart LR
    subgraph them["❌ Everyone else: one private machine per agent"]
        c1["🤖 agent"]:::agent --> o1["cold build program"]:::bad
        c2["🤖 agent"]:::agent --> o2["cold build program"]:::bad
        c3["🤖 agent"]:::agent --> o3["cold build program"]:::bad
        o1 & o2 & o3 --> boom["💥 out of memory<br/>computer falls over"]:::boom
    end
    style them fill:#fef2f2,stroke:#dc2626,stroke-width:2px
    classDef agent fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef bad fill:#fee2e2,stroke:#dc2626,color:#7f1d1d
    classDef boom fill:#dc2626,stroke:#991b1b,color:#ffffff,font-weight:bold
```

```mermaid
flowchart LR
    subgraph us["✅ Us: one shared build service, kept ready"]
        k1["🤖 agent"]:::agent --> gate
        k2["🤖 agent"]:::agent --> gate
        k3["🤖 agent"]:::agent --> gate
        k4["🤖 agent"]:::agent --> gate
        gate["🚦 gatekeeper<br/>(only as many as fit)"]:::gate --> svc["⚡ shared build service<br/>already started, stays ready"]:::service
        svc --> happy["😀 stays fast,<br/>doesn't fall over"]:::good
    end
    style us fill:#f0fdf4,stroke:#16a34a,stroke-width:2px
    classDef agent fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef gate fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef service fill:#ede9fe,stroke:#7c3aed,color:#4c1d95
    classDef good fill:#dcfce7,stroke:#16a34a,color:#14532d
```

**One build request, start to finish:**

```mermaid
flowchart TB
    a["Agent makes a change"]:::agent --> b["Sends a request:<br/>'build & test this'"]:::agent
    b --> c{"Gatekeeper:<br/>is there room?"}:::gate
    c -->|yes| d["Shared service builds & tests it<br/>(fast — already running)"]:::service
    c -->|"no, too busy"| e["Told to wait<br/>and try again shortly"]:::bad
    d --> f["Result back:<br/>👍 passed / 👎 found a problem"]:::good
    e --> b
    classDef agent fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef gate fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef service fill:#ede9fe,stroke:#7c3aed,color:#4c1d95
    classDef good fill:#dcfce7,stroke:#16a34a,color:#14532d
    classDef bad fill:#fee2e2,stroke:#dc2626,color:#7f1d1d
```

### (b) Combining everyone's work into one project — safely

All the agents work on the **same project**, each on their own copy (a "branch"). When an agent's
change passes its tests, it asks for the change to be **added to the main shared copy**. A
**coordinator** checks the change still works *combined with everyone else's* before keeping it — and
if it breaks something, it's quietly undone so **the main copy never stops working**.

```mermaid
flowchart TB
    a["Agent finishes a change<br/>on its own copy (branch)"]:::agent --> b["Tests it — passes ✓"]:::good
    b --> c["Asks to add it to<br/>the main shared project"]:::agent
    c --> d{"Coordinator: does it still work<br/>combined with everyone else's?"}:::gate
    d -->|yes| e["Added to the main project 🎉"]:::good
    d -->|"no — it breaks something"| f["Quietly undone;<br/>main project stays working"]:::bad
    classDef agent fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef gate fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef good fill:#dcfce7,stroke:#16a34a,color:#14532d
    classDef bad fill:#fee2e2,stroke:#dc2626,color:#7f1d1d
```

### (c) The service sizes itself

The build service watches the computer's memory while it runs. If memory gets tight it **does a bit
less at once** (so it never crashes); when there's spare room it **does more** (so the machine isn't
wasted). You don't have to tune it by hand, and it keeps working as the project grows.

```mermaid
flowchart LR
    tight["memory getting tight"]:::bad -->|"do less"| svc["⚖️ build service<br/>adjusts itself"]:::service
    spare["spare capacity"]:::good -->|"do more"| svc
    svc --> safe["never crashes,<br/>never wastes the box"]:::good
    classDef service fill:#ede9fe,stroke:#7c3aed,color:#4c1d95,font-weight:bold
    classDef good fill:#dcfce7,stroke:#16a34a,color:#14532d
    classDef bad fill:#fee2e2,stroke:#dc2626,color:#7f1d1d
```

---

## 0b. Beyond the executor: the workflow + adaptive layers (built on top)

The warm executor is the foundation. Two layers ride on it to make the product the full thing —
*many agents, one project, branch → test → PR → **merge to main**, fast and without conflicts, on a box
that sizes itself*. Deep dives: [ARCHITECTURE.md](ARCHITECTURE.md), [SETUP.md](SETUP.md).

- 🟣 **Merge orchestrator** (`dev.arxael.merge`, surface `/merge/{register,submit,status}`). Agents submit
  branch-tested PRs; the orchestrator lands them on a shared `main` without conflicts. It **auto-routes**
  each PR by its dependency-closure size (auto-discovered from the project's Gradle graph): small closure →
  **optimistic land + module-scoped async gate** that auto-reverts a break (instant, no cascade); large
  closure → **batched gate-then-land** that never breaks main and attributes the culprit on a red batch.
  Gate tests run on the executor's reserved high-priority lane so landings never starve behind branch-tests.

- 🟡 **Adaptive auto-sizing** (`dev.arxael.autosize`). The static box-derived bound is only a starting point;
  a governor adapts the live concurrency bound + build-workers (coupled `C·W ≈ cores`) to **measured memory
  pressure** within hard caps `[floor, ceiling]`, learns and **persists** the real per-build footprint, and
  scales the overload timeout to observed build duration. So density tracks the box's real limit AND the
  project's growth — shrinking before OOM, growing into spare capacity, both ways.

- 🟦 **Shared-but-unlocked dep cache.** Per-worktree Gradle homes are the **default** — they remove the
  cross-process cache lock (the concurrency ceiling that capped builds at ~8) but would re-download deps
  (Maven 429). So the daemon serves deps **read-only** to every per-worktree build (`GRADLE_RO_DEP_CACHE`)
  and a background **consolidator** folds freshly-downloaded deps into that shared cache — re-downloads
  converge to ~zero: shared deps, no lock, no re-download.

- 🟢 **Change-aware test scoping.** The merge gate looks at *what a PR actually changed* (its diff): a
  doc-only change (README, docs, images) **skips the gate entirely** (it can't break a test), and a code
  change is tested against only the modules it actually touches — so it doesn't re-test everything for a
  small or doc change.

---

## 1. Repo / build topology

```mermaid
flowchart TB
    subgraph repo["arxael (Gradle, JVM 21 / Kotlin 1.9.24)"]
        settings["settings.gradle.kts<br/>includes :core"]:::cfg
        rootbuild["build.gradle.kts (root)<br/>plugin versions only"]:::cfg
        subgraph core[":core  (the daemon: executor + merge + autosize)"]
            corebuild["core/build.gradle.kts<br/>plugins: kotlin · serialization<br/>application · jacoco · pitest<br/>deps: gradle-tooling-api 8.10.2"]:::cfg
        end
        wrapper["gradlew<br/>(pinned Gradle 8.10.2)"]:::cfg
    end

    settings --> core
    rootbuild -.applies plugins.-> core
    wrapper --> settings

    corebuild ==>|installDist| daemon([core daemon binary]):::service
    corebuild ==>|test + jacocoTestReport| cov([coverage XML]):::artifact
    corebuild ==>|pitest| mut([mutation XML]):::artifact

    style core fill:#ede9fe,stroke:#7c3aed,stroke-width:2px
    classDef cfg fill:#f1f5f9,stroke:#475569,color:#0f172a
    classDef service fill:#ede9fe,stroke:#7c3aed,color:#4c1d95,font-weight:bold
    classDef artifact fill:#cffafe,stroke:#0891b2,color:#164e63
```

## 2. Supporting infrastructure (around the build)

```mermaid
flowchart LR
    subgraph scripts["scripts/  (operational lifecycle)"]
        arxael["arxael<br/>up · verify · CLI"]:::script
        install["install.sh<br/>JDK + Gradle + installDist"]:::script
        smoke["smoke.sh<br/>e2e acceptance"]:::script
        quality["quality.sh<br/>coverage · mutation · trivy"]:::script
    end

    subgraph bench["bench/  (benchmarks + real-world validation)"]
        benchpy["bench.py / run_sweep.sh<br/>density sweep (arm×agents×cores)"]:::bench
        mergesim["merge_sim.py<br/>merge-strategy prototype"]:::bench
        realworld["realworld_oss / caffeine / chaos<br/>real OSS + crash-recovery proofs"]:::bench
        analyze["analyze.py / sampler.py<br/>collapse-point + resource sampling"]:::bench
    end

    subgraph fixtures["fixtures/"]
        hello["gradle-hello<br/>smoke fixture (ARXAEL_SMOKE_OK)"]:::fixture
    end

    subgraph docs["docs/"]
        d1["OVERVIEW · ARCHITECTURE"]:::cfg
        d2["SETUP · LIMITATIONS"]:::cfg
    end

    arxael ==>|installDist + register| daemon([core daemon]):::service
    smoke -->|/invoke| daemon
    smoke --> hello
    quality -->|gradlew test/jacoco/pitest| repo[(:core build)]:::service
    benchpy -->|warm arm: /invoke| daemon
    benchpy -->|container arm: docker gradle| ctr([per-agent containers]):::bad
    realworld -->|/invoke + /merge| daemon

    classDef script fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef bench fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef fixture fill:#dcfce7,stroke:#16a34a,color:#14532d
    classDef cfg fill:#f1f5f9,stroke:#475569,color:#0f172a
    classDef service fill:#ede9fe,stroke:#7c3aed,color:#4c1d95,font-weight:bold
    classDef bad fill:#fee2e2,stroke:#dc2626,color:#7f1d1d
```

## 3. Runtime — the whole daemon (one process)

> **Rule:** concurrency comes from **N bounded warm servers, one per worktree**,
> each serialized by a lock — *never* from multiplexing one process across concurrent callers.
> Everything below lives in one long-lived process behind the loopback HTTP surface.

```mermaid
flowchart TB
    fleet["AI agent fleet"]:::agent

    subgraph daemon["core daemon  (dev.arxael, loopback 127.0.0.1:8723)"]
        subgraph api["HTTP surface (InvokeServer)"]
            rbuild["/invoke · /warmup"]:::entry
            rmerge["/merge/register · submit · status"]:::entry
            robs["/health · /metrics · / (API card)"]:::entry
        end
        allow["ArgAllowlist<br/>fail-closed validation"]:::gate

        subgraph execlayer["Warm executor (foundation)"]
            exec["WarmExecutor<br/>AdjustableSemaphore bound<br/>worktree → server · LRU evict"]:::core
            subgraph servers["N WarmServers (1 per worktree, lock-serialized)"]
                ws1["WorktreeServer A"]:::server
                ws2["WorktreeServer B"]:::server
            end
            adapters["Adapter SPI<br/>gradle · gradlew · command · noop"]:::core
        end

        gov["AdaptiveGovernor<br/>AIMD: resize bound to memory pressure<br/>learn footprint · couple workers"]:::core
        depcache["Shared RO dep cache<br/>Warmer + Consolidator"]:::artifact

        subgraph mergelayer["Merge orchestrator (dev.arxael.merge)"]
            router["MergeRouter<br/>auto-route by closure size"]:::core
            orch["MergeOrchestrator<br/>optimistic-land + async revert / batched gate"]:::core
            journal["PrJournal<br/>crash recovery"]:::aux
        end

        watchdog["Watchdog (probe + recover)"]:::aux
        eventlog["EventLog (append-only JSONL)"]:::aux
    end

    fleet -->|build/test| rbuild
    fleet -->|land a PR| rmerge
    rbuild --> allow --> exec
    exec --> ws1 & ws2 --> adapters
    adapters -->|"warm conn · per-worktree home"| build([gradle / pytest / go / …]):::artifact
    depcache -.read-only deps.-> adapters
    gov -.resizes live.-> exec
    rmerge --> router --> orch
    orch -->|gate on reserved HIGH lane| exec
    orch -.journals.-> journal
    orch -->|"land / auto-revert"| main([shared main]):::artifact
    watchdog -.health.-> servers
    exec -.events.-> eventlog
    orch -.events.-> eventlog

    style daemon fill:#faf5ff,stroke:#7c3aed,stroke-width:2px
    classDef agent fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef entry fill:#bfdbfe,stroke:#2563eb,color:#1e3a8a,font-weight:bold
    classDef gate fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef core fill:#ede9fe,stroke:#7c3aed,color:#4c1d95,font-weight:bold
    classDef server fill:#f3e8ff,stroke:#9333ea,color:#581c87
    classDef aux fill:#f1f5f9,stroke:#475569,color:#0f172a
    classDef artifact fill:#cffafe,stroke:#0891b2,color:#164e63
```

## 4. The merge workflow (auto-route)

> Two strategies, picked automatically per PR by its dependency-closure size. Optimistic-land gives
> the **latency**; the branch-gate + module-scoped verify give the **soundness** (main never breaks).

```mermaid
flowchart TB
    pr["PR submitted<br/>/merge/submit (branch, module)"]:::agent --> router{"MergeRouter:<br/>dependency-closure size?"}:::gate

    router -->|"small / independent"| opt["Optimistic: land NOW,<br/>verify async"]:::core
    router -->|"large / deep chain"| batch["Batched: gate-then-land<br/>(1 test per batch)"]:::core

    opt --> ascan{"module-scoped<br/>async gate"}:::gate
    ascan -->|green| done1["stays landed ✓<br/>~0.1s time-to-land"]:::good
    ascan -->|red| revert["auto-revert just this PR<br/>(no cascade)"]:::bad

    batch --> bgate{"gate the batch"}:::gate
    bgate -->|green| done2["whole batch lands ✓<br/>main never breaks"]:::good
    bgate -->|red| culprit["CulpritAttribution names<br/>the bad PR, re-gates the rest"]:::bad

    classDef agent fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef gate fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef core fill:#ede9fe,stroke:#7c3aed,color:#4c1d95,font-weight:bold
    classDef good fill:#dcfce7,stroke:#16a34a,color:#14532d
    classDef bad fill:#fee2e2,stroke:#dc2626,color:#7f1d1d
```

### Key invariants baked into the runtime
- **Per-worktree Gradle home is the default** — removes the shared-home cross-process cache lock that
  capped concurrency at ~8; the box becomes CPU-bound instead of lock-bound.
- **Shared *read-only* dep cache** (`GRADLE_RO_DEP_CACHE`) — per-worktree homes don't re-download; a
  self-filling consolidator converges re-downloads to ~zero (closes the Maven-429 blocker).
- **The concurrency bound is adaptive** — AIMD governor resizes it to measured memory pressure within
  `[floor, ceiling]`, shrinking *before* OOM and growing into spare capacity.
- **Merge auto-routes** — optimistic-land + module-scoped async revert (fast, small closures) vs batched
  gate-then-land + culprit attribution (sound, large closures); gates run on a reserved **high** lane so
  landings never starve behind branch-tests.
- **Branch-gate = soundness, optimistic-land = latency** — together: instant landings, main never broken.
- **PrJournal survives restart** — re-enqueues submitted-but-unfinished and landed-but-unverified PRs.
- **Change-aware gate** — a doc-only PR skips the gate (can't break a test); a code PR is tested against
  only the modules its diff touches, not the whole project.
- **Warm connection never closed per-invoke**; **Watchdog probes + recovers** off the hot path
  (quarantine → drop wedged → recreate fresh); **EventLog is append-only** — the replayable source of truth
  (also projected to Prometheus `/metrics`).

---

*Generated as a high-level map; component names follow `core/src/main/kotlin/dev/arxael/…`.*
