# Deliverable #1 — container-per-agent vs shared-warm benchmark

Measures the headline claim: **how many concurrent agents one box sustains** under each model.
Density, not speed.

## The two arms (fair by construction)

- **warm** — one arxael daemon (the warm bounded executor); `M` agents each `POST /invoke` against
  their own worktree. Concurrency capped by the executor; excess agents queue. Gradle daemons are
  reused across worktrees, so memory tracks the *bound* (~cores), not the agent count.
- **container** — the **fair steelman**: `M` long-lived containers, each a gradle daemon
  **warm within its life** (NOT cold per call), each holding its own daemon + cache footprint.
  Memory grows ~linearly with `M` → the collapse arm.

Both run the **identical generated fixture build** as the unit of work, are pinned to the same
`0..C-1` cpuset (`taskset` / `--cpuset-cpus`), and are **pre-warmed** once per worker before timing —
so a "task" is the same work and the window measures steady state.

## Workload profiles (`--profile`)

The heavy tasks agents actually run, so density is realistic:
`test` (compile + JUnit), `coverage` (test + JaCoCo), `mutation` (PIT — CPU/RAM heavy).

## Metrics (per cell, steady-state window)

goodput (tasks/min), p50/p95/p99 latency, wedge rate — plus the full sampler: peak RAM + **swap**,
CPU, load, **per-JVM RSS + gradle-daemon count** (the memory story, captured uniformly since
container JVMs are host-visible), and the **disk chokepoint** (IOPS / MB/s / %util — the EBS ceiling).

## Run it

```bash
# one cell
python3 bench/bench.py --arm warm --agents 16 --cores 32 --profile test --run-dir bench/out/x

# full sweep (both arms, agent ramp, clean room between cells) + analysis
ARMS="warm container" AGENTS="1 2 4 8 16 32 48 64" CORES=32 PROFILE=test bench/run_sweep.sh
python3 bench/analyze.py bench/out/test/results.jsonl
```

## Validation suite (proof, not assertion — each is one runnable command)

These prove the product end-to-end through the REAL shipped code (not the `merge_sim` prototype):

```bash
bash bench/lang_smoke.sh            # multi-language /invoke on real toolchains (pytest/go/cargo/npm) -> ALL GREEN
python3 bench/lang_merge_smoke.py   # the FULL merge workflow on pytest: good lands, bad bounces, main green
python3 bench/chaos_recovery.py     # SIGKILL the daemon mid-merge -> restart recovers, main green, 0 reverts
python3 bench/merge_http_load.py --auto-warm --agents 8   # throughput (merges/min) through the real orchestrator
bash bench/realworld_container.sh   # isolated container, kit built from source, synthetic wide-DAG, many agents
bash bench/realworld_oss.sh 16 12 300  # isolated container + REAL google/gson, 16 closed-loop agents, mvn gate
bash bench/install_container.sh     # clean ubuntu -> one command -> READY -> arxael verify (fresh-box claim)
```

Headline results (see `docs/ARCHITECTURE.md` "Real-world & robustness validation"): real gson run = 48/48 good
PRs landed, 2/2 bad caught, 0 reverts/errors, main green; fresh-box READY in 70s; per-worktree-home A/B
+46–83% merges/min. **Honest note on `ARXAEL_MERGE_BATCH_CAP`:** raising it (8→16) is a no-op at ~16
*closed-loop* agents (the queue stays shallow, so agent-count × gate-latency bounds throughput, not the cap);
it only helps when the queue is genuinely deep (open-loop / far more agents than gate capacity).

## Observability bundle (no dark spots)

Each cell writes to `bench/out/<profile>/<arm>-c<C>-a<A>/`:
`result.json`, `timeseries.jsonl` (1 Hz sampler), `atop.raw` (full-system replay: `atop -r atop.raw`),
`pidstat.log` (per-process), and the warm daemon's `daemon.log` + `events.jsonl`.

## Notes / honesty

- On a 246 GB box, literal container OOM needs many agents; use `--mem-gb` to model a realistically
  sized "box you own" (cgroup memory budget) so the collapse point lands at a sensible agent count.
- Stray host gradle daemons are killed between cells (`run_sweep.sh`) so counts aren't contaminated.
