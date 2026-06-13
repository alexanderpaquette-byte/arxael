# SETUP

## Quick start (no jargon — start here)
If you just want it running, you only need four words. From inside your project folder:
```bash
scripts/arxael up        # turn it on, and connect this project
scripts/arxael status    # is it working? any problems? (plain English)
scripts/arxael logs      # what just happened
scripts/arxael verify    # prove it works: unit tests + acceptance smoke + multi-language end-to-end
scripts/arxael stop      # turn it off (cleans up after itself)
```
That's it. `arxael up` finds your build tool, builds the helper the first time, starts it on a free port,
and connects the git project you're standing in — no ports, processes, daemons, or JSON to think about. It
cleans up after itself on `stop` (and even on a crash, the next `up` tidies leftovers). Everything below is
for power users / CI who want the knobs.

---

# Agent-native setup (wraps the deterministic install script)

A coding agent reads this on a fresh box to detect prereqs, install, configure, and smoke-test
the substrate. It WRAPS the deterministic `scripts/install.sh` (the source of truth) — power users / CI
run that script directly and skip this prose. The acceptance smoke test is deterministic and
agent-independent (start daemon → `/invoke` a trivial build → assert green).

## 0. What you're standing up
A long-lived daemon (`core/`) on loopback `127.0.0.1:<port>`; agents `POST /invoke` build/test work and it
runs them on N warm bounded per-worktree servers. See `docs/OVERVIEW.md` for the shape, `docs/ARCHITECTURE.md`
for the why, `docs/ARCHITECTURE.md` for the tuned config + limits.

## 1. Install (idempotent)
```bash
scripts/install.sh          # JDK 21 + pinned Gradle 8.10.2 (/opt/gradle) + ./gradlew :core:installDist
```
Prereqs auto-detected: installs OpenJDK 21 if no `java`; installs Gradle if missing; always (re)builds the
daemon. Result: launcher at `core/build/install/core/bin/core`.

## 2. Start the daemon
```bash
ARXAEL_GRADLE_HOME=/opt/gradle/gradle-8.10.2 core/build/install/core/bin/core
# GET /health -> {"ok":true, ...}; POST /invoke; POST /shutdown (all loopback)
```
Observability: every surface emits to `<stateDir>/events.jsonl` (full audit trail); `GET /metrics` is a
native Prometheus scrape target (drop-in `ops/prometheus.yml` + `ops/grafana-dashboard.json`). Adapters
shipped: `gradle`, `gradlew` (project wrapper), `maven`, `pytest`, `cargo`, `go`, `vitest`, `npm`, `make`, `exec` (set `"adapter":"…"` per invoke).
`GET /health` shows the
live bound + adaptive state and a `recentErrorCount` + last few faults (no grepping needed); `GET
/merge/status` shows landed/reverts/bounces/`errors`/time-to-land. Cleanup is automatic: the shutdown hook
closes worktree-servers/worktrees/governor/log AND **kills this daemon's Gradle build-daemons immediately**
(scoped via `/proc` to homes under `<stateDir>`, so it never touches other Gradle work) instead of waiting
out the 120s idle-timeout; orphans from a crash are pruned on next register; `scripts/reap-daemons.sh` is a
manual sweep.

Warm-up (optional, kills the per-worktree cold-start tax): the agent-runner can `POST /warmup` each agent's
worktree at setup (in parallel) to pre-spawn its Gradle daemon off the critical path, so the agent's first
real build is warm (measured: ~21s cold → ~0.25s warm). Empty `tasks` default to a cheap daemon-spawn
(`help`); pass `["testClasses"]` to also warm the per-worktree build cache.
```bash
curl -sX POST 127.0.0.1:8723/warmup -H 'Content-Type: application/json' \
  -d '{"adapter":"gradle","worktree":"/path/to/agent-worktree"}'
```

## 3. Smoke test (acceptance)
```bash
scripts/smoke.sh            # start daemon -> /invoke a trivial build -> assert green; prints PASS/ALL GREEN
```

## 4. Configuration (all ARXAEL_* env, box-derived defaults)
The bound and every concurrency knob scale to the box. Key knobs (see `BoxConfig.kt`):
| env | default | what |
|---|---|---|
| `ARXAEL_CORES` | detected | cores the bound scales to |
| `ARXAEL_AGENTS_PER_CORE` | 1.0 | coreBound = round(cores × this) |
| `ARXAEL_BUDGET_PCT` | 100 | **easy dial**: use this % of cores + build-RAM (60 = leave ~40% for your other apps) |
| `ARXAEL_MAX_CONCURRENT` | min(coreBound, memBound) | **exact** in-flight build count (overrides the dial) |
| `ARXAEL_PER_BUILD_MB` | 1536 | per-build footprint for memBound (use ~1.5–4 GB for parallel builds) |
| `ARXAEL_ACQUIRE_TIMEOUT_MS` | watchdog×30 | overload timeout (raise above honest build time) |
| `ARXAEL_DAEMON_IDLE_SEC` | 120 | build-daemon idle timeout (bounds the daemon leak) |
| `ARXAEL_PER_WORKTREE_HOME` | **true** | per-worktree Gradle home (removes the shared-home cache lock; self-filling shared dep cache — see below). Set `false` for the old shared home |
| `ARXAEL_RO_DEP_CACHE` | (auto) | pin a read-only dep cache; if unset with per-worktree-home on, the daemon auto-wires + self-fills `<stateDir>/shared-deps/caches` |
| `ARXAEL_BUILD_CACHE` | true | inject `--build-cache` |
| `ARXAEL_RESERVED_HIGH` | 0 | permits reserved for `priority:"high"` invokes (merge-gate lane) |
| `ARXAEL_ADAPTIVE` | true | adaptive auto-sizing: a governor adapts the bound + build-workers to live memory pressure |
| `ARXAEL_CONCURRENCY_FLOOR` | 1 | **hard floor** — governor never goes below (always make progress) |
| `ARXAEL_CONCURRENCY_CEILING` | coreBound | **hard ceiling** — governor never goes above (operator's absolute cap) |
| `ARXAEL_GOVERNOR_MS` | 3000 | governor sampling interval |
| `ARXAEL_ACQUIRE_TIMEOUT_MULT` | 4 | adaptive overload timeout = observed build time × this (floored at `ACQUIRE_TIMEOUT_MS`) |
| `ARXAEL_ACQUIRE_TIMEOUT_CAP_MS` | 600000 | hard cap on the adaptive overload timeout (a wedged box still fails closed) |
| `ARXAEL_MERGE_MODE` | balanced | merge risk posture: `conservative` = all batched (max safety), `balanced` = self-tuning default, `fast` = optimistic-leaning |
| `ARXAEL_GATE_FILL_ROUTING` | on (under balanced) | land optimistically when pending work fills the gate pool, else batch |
| `ARXAEL_GATE_FILL_HYSTERESIS` | on (under balanced) | sticky regime — don't flap between optimistic and batched |
| `ARXAEL_BATCHCAP_AWARE` | on (under balanced) | force batch when a batch dominates the gate pool |
| `ARXAEL_BATCHCAP_DOMINANCE_FACTOR` | 2.0 | the dominance threshold for batchCap-awareness |
| `ARXAEL_ENGINE_VERSION` | (latest release) | **pin a specific, vetted engine version** — honoured by the npm install + the update check; updates are NEVER automatic, so a pinned build stays put |
| `ARXAEL_NO_UPDATE_CHECK` | unset | set to `1` to disable the once/day "update available" check (also disabled by `DO_NOT_TRACK`, `CI`, or any non-interactive/agent run — agents never beacon) |
| `ARXAEL_REPO` | `alexanderpaquette-byte/arxael` | the GitHub repo polled for the latest release (update check + engine fetch) |

## 5b. Version & updates (introspection, notify-only)
The running daemon reports its engine version on `GET /health` (`"version"`) and `GET /metrics`
(`arxael_build_info{version}`). The CLI: `arxael version` shows the CLI / engine / running-daemon versions and
**warns if the warm daemon is older than what's installed** (restart to adopt). An **interactive-only, once/day**
check compares your engine against the latest GitHub release and prints "update available" — it **never
auto-updates** (so vetted/pinned builds stay put; pin with `ARXAEL_ENGINE_VERSION`), is **off for agents /
non-TTY / `CI`**, and is opt-out (`ARXAEL_NO_UPDATE_CHECK=1` / `DO_NOT_TRACK`). `arxael upgrade` prints how to
update; it never installs anything on its own. See LIMITATIONS "Network access" for the privacy posture.

## 5c. Adaptive auto-sizing (tracks the box AND the project's growth)
The startup bound (`min(coreBound, memBound)`) is only a STARTING estimate. A governor then adapts the live
concurrency bound to **measured** memory pressure within the hard caps `[floor, ceiling]`: available memory
below the headroom floor → back off (multiplicative); demand waiting + measured room + spare CPU → grow by
one. It **learns the real per-build footprint** (EWMA), so as the agents' project grows — more modules,
heavier tests, bigger builds — the footprint rises, available memory falls, and the bound self-corrects DOWN
*before* it can OOM/swap-wedge (the failure the campaign hit with a static 1 GB estimate), then grows back
when builds get lighter or on a bigger box. Concurrency C and workers-per-build W are coupled (C·W ≈ cores):
as memory forces C down, each build gets more workers (fewer-but-faster builds keep the box saturated, not
starved). `/health` reports the live `concurrencyTarget` and `buildWorkers`; resizes are logged as
`governor_resize` events. Set `ARXAEL_ADAPTIVE=false` to pin the static bound. The learned footprint is
persisted (`<stateDir>/learned-footprint-mb`) so a restart starts calibrated to the project as it's grown.
The **overload timeout adapts to build duration** too: it's `observed-build-time × ARXAEL_ACQUIRE_TIMEOUT_MULT`
floored at `ARXAEL_ACQUIRE_TIMEOUT_MS` and capped at `ARXAEL_ACQUIRE_TIMEOUT_CAP_MS`, so as builds lengthen
a caller queued behind them isn't falsely shed as OVERLOADED.

## 5d. Run it as a supervised service (self-healing single-box HA)
For an always-on box, run the daemon under systemd so it auto-restarts on crash AND on wedge:
```bash
sudo scripts/install-service.sh         # installs arxael.service (Restart=always) + a 30s liveness timer
#   systemctl status arxael · journalctl -u arxael -f · sudo scripts/install-service.sh --uninstall
```
A crash → systemd restarts → `PrJournal` re-enqueues + re-gates unfinished PRs (no unverified change left on
`main`, validated under fault injection). A wedge (process alive but `/health` stops answering) → the
liveness timer restarts it. Same one box, now self-healing (downtime = restart seconds).

## 6. Power users / CI

## 4a. How it sizes itself (the smart default — you usually need nothing)
Out of the box it tunes itself to your machine, and keeps tuning while it runs. In plain terms:
1. **At startup it measures the machine** — counts your cores and reads your total RAM — and sizes how many
   builds it runs at once to fit (never more than the cores or the memory can hold).
2. **While running it watches real pressure** — free memory, CPU load, and disk wait — and **learns how big
   your project's builds actually are** (and remembers that across restarts). It nudges the number **up** when
   there's spare room and demand, and **down before** memory/CPU/IO would ever overload — automatically, both
   directions, within safe hard limits.
3. **Net effect:** it safely uses as much of the machine as is *productive* and backs off the moment it
   isn't — so it's fast without tipping the box over. This is well-calibrated; **most people set nothing.**

## 4b. Sharing the box: telling it to use less (optional)
The only reason to override the default is if the daemon **shares the box with your own project or other
apps** and you want to reserve room for them. Then cap it — a ladder from easy to exact (each is the hard
cap the smart auto-sizing then works *within*):
- **Easy (a share):** `scripts/arxael up --budget 60` → ~60% of cores and build-RAM. (`ARXAEL_BUDGET_PCT=60`.)
- **Exact:** `--cores 8` (`ARXAEL_CORES=8`), `--mem 16g` (`ARXAEL_USABLE_RAM_MB=16384`).
- **Finest:** `ARXAEL_MAX_CONCURRENT` (exact in-flight builds), `ARXAEL_PER_BUILD_MB` (RAM per build),
  `ARXAEL_AGENTS_PER_CORE`, `ARXAEL_CONCURRENCY_FLOOR/CEILING` (hard adapt limits).
`arxael up` with no flag, run by a human at a terminal, **asks** which of these you want; an agent/script
gets the whole machine unless it passes a flag. `arxael status` and `/health` show what was resolved.

## 5. Tuned config (from the limit-finding — `docs/ARCHITECTURE.md`)
For many concurrent agents the throughput-tuned config is:
- **Per-build parallelism scaled to cores** (each build should use the box; a 1-worker build caps it at ~40% CPU).
- **`ARXAEL_PER_WORKTREE_HOME=true` (now the DEFAULT)** removes the shared-`GRADLE_USER_HOME` cross-process
  cache lock — the proven concurrency ceiling (~8 builds). Per-worktree homes would
  otherwise re-download dependencies and hit Maven Central **429s at scale**, so the daemon makes it safe
  automatically: at startup (per-worktree on, no `ARXAEL_RO_DEP_CACHE` pinned) it wires a daemon-global
  read-only shared dep cache (`<stateDir>/shared-deps/caches`) and runs a background **consolidator** that
  folds freshly-downloaded artifacts from each per-worktree home into it — so the cache **self-fills from
  real usage** and re-downloads converge to ~zero for *any* `/invoke`. `/merge/register` also proactively
  pre-warms that same cache. Pin `ARXAEL_RO_DEP_CACHE` to use your own cache (disables the auto-wire); set
  `ARXAEL_PER_WORKTREE_HOME=false` to restore the old shared home. **Validated A/B** (daemon self-warmed its cache, 60s,
  driven through the real `MergeOrchestrator`): at **6 agents** per-worktree = 20.4 merges/min vs shared 14.0
  (**+46%**); at **12 agents** per-worktree = 21.4 vs shared 12.4 (**+73%** — the gap widens with concurrency
  as the lock bites harder; shared-home throughput *drops* while per-worktree holds). 0 reverts in all runs.
- Concurrency ≈ cores/2 with ~2 workers/build (total worker-slots ≈ cores; oversubscription thrashes).
This roughly doubles concurrent test throughput vs the untuned default.

## 5b. Merge orchestrator (branch → test → PR → merge to main)
The daemon also lands many agents' PRs onto one shared `main`, fast and without conflicts (the auto-route
design in `docs/ARCHITECTURE.md` / `docs/ARCHITECTURE.md`). The agent-runner creates a bare repo holding
`main`, registers it once, then agents submit branch-tested PRs. All loopback, all on the warm substrate.
```bash
# register the project — forwardDeps OPTIONAL: omit it and the daemon auto-discovers the module graph from
# the project itself (correct-by-construction; supply it explicitly only to override). threshold = the
# closure size that BOUNDS optimistic eligibility; under the default self-tuning balanced mode it only
# bounds the optimistic set, and live load decides batch-vs-optimistic within that bound.
# Returns 202 IMMEDIATELY (a bad repo with no 'main' is 422). The cold-Gradle probe + dep-cache warm run in
# the background — poll /merge/status until "registerState":"ready" before submitting (the first build is cold).
curl -sX POST 127.0.0.1:8723/merge/register -H 'Content-Type: application/json' \
  -d '{"repo":"/path/to/bare.git","threshold":4,"gateWorktrees":4}'
# submit a branch-tested PR (module = its Gradle path; null => routed batched/full)
curl -sX POST 127.0.0.1:8723/merge/submit -H 'Content-Type: application/json' \
  -d '{"branch":"feature-x","module":":app","agentId":"agent7"}'
curl -s 127.0.0.1:8723/merge/status     # registerState + landed / reverts / time-to-land / routing split
```
Routing is load-adaptive and self-tuning under the default `balanced` mode: a PR lands optimistically
(instant, verifies async on a module-scoped gate that auto-reverts a break) when pending work fills the
gate pool, and goes through a batched gate-then-land (never breaks main, attributes the culprit on a red
batch) when load is low — with hysteresis so the regime doesn't flap and batchCap-awareness that forces
batch when a batch dominates the pool; closure size bounds optimistic eligibility. `ARXAEL_MERGE_MODE=conservative`
batches everything, `fast` leans optimistic. Gate tests run on the reserved high-priority lane
(`ARXAEL_RESERVED_HIGH` > 0 so landings never starve behind agent branch-tests). `ARXAEL_MERGE_GATE_ADAPTER`
defaults to `gradle` but can be any **named** adapter (`maven`, `pytest`, `cargo`, `go`, `npm`, …) — each
adapter's default command is overridable via `ARXAEL_<NAME>_CMD` (e.g. `ARXAEL_MAVEN_CMD="mvn -q -pl core -am
test"` to scope a multi-module Maven gate). `ARXAEL_MERGE_BATCH_CAP` (default 16) caps how many PRs amortize
one batched gate — raise it on slow (minute-scale) real builds so each gate lands more PRs; bisection keeps a
red large batch cheap. For non-gradle the
gate runs the adapter's **full default test suite** (gradle's incremental `:modX:test` module-scoping is a
JVM-only optimization), those PRs route **batched**, and a red gate with no parseable culprit safely treats
the whole batch as suspect — so "branch → test → PR → merge to main" works multi-language, just less granular
than gradle's per-module attribution.

## 6. Power users / CI
Run `scripts/install.sh` then `scripts/smoke.sh` directly; set the configuration env to taste. Quality gates:
`scripts/quality.sh` (coverage + mutation + trivy).
