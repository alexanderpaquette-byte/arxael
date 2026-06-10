# AGENTS.md — for an AI agent with zero context (start here)

## What this is (one paragraph)
This repo runs **one always-on local helper** (a "daemon") that many AI agents share to work on **one
project** at the same time: each agent makes a branch, **tests** it, opens a **PR**, and the helper
**merges it to `main` fast and without conflicts**. Instead of every agent cold-starting its own build (which
exhausts the machine), all builds funnel into this one warm, bounded executor. You are one of those agents.

## Get it running (one command)
```bash
scripts/arxael up        # builds the helper on first run, starts it, connects the project you're in
scripts/arxael status    # confirm it's on; shows capacity + any problems, in plain English
```
`arxael up` prints the shared "hub" repo path and the port. If you skip the CLI, the daemon is
`core/build/install/core/bin/core` (after `scripts/install.sh`); it listens on loopback `127.0.0.1:<port>`
(default 8723; `arxael up` may pick the next free port — see `~/.arxael/port`).

## How much of the machine it uses
**Default = nothing to set.** It measures the machine at startup, sizes the number of concurrent builds to
fit cores + RAM, then while running learns your builds' real footprint and nudges up/down with live memory,
CPU, and disk pressure — using as much as is productive and backing off before it overloads. It's
well-calibrated; most agents leave it alone.

Override **only** if the daemon shares the box with the user's project or other apps and you want to reserve
room — a ladder from easy to exact (whatever you set is the cap the auto-sizing then works within):
- **Easy (a share):** `scripts/arxael up --budget 60` → ~60% of cores **and** build-RAM. (Env: `ARXAEL_BUDGET_PCT=60`.)
- **Exact:** `--cores 8` (`ARXAEL_CORES=8`) · `--mem 16g` (`ARXAEL_USABLE_RAM_MB=16384`).
- **Finest (precise tuning):** `ARXAEL_MAX_CONCURRENT=N` (exact in-flight builds), `ARXAEL_PER_BUILD_MB`
  (memory budgeted per build), `ARXAEL_CONCURRENCY_FLOOR/CEILING` (hard adapt limits) — see `docs/SETUP.md`.
Whatever you pick is the cap; the governor adapts **within** it. `GET /health` shows the resolved
`budgetPct`, `bindingConstraint`, cores, RAM, and live target.

## Discover the live API at runtime (no docs needed)
The daemon is self-describing. With just the port:
```bash
curl -s http://127.0.0.1:8723/        # returns a JSON card: what it is + every endpoint + request shapes
```
Trust that card over this file if they ever differ — it's generated from the running code.

## Your loop as an agent (the whole contract)
You work in a git worktree/checkout of the project. To land a change:

1. **Test it** on the shared executor (don't run gradle yourself — route it here so the box stays bounded):
   ```bash
   curl -sX POST 127.0.0.1:8723/invoke -H 'Content-Type: application/json' \
     -d '{"adapter":"gradle","worktree":"/abs/path/to/your/checkout","tasks":["test"],"agentId":"you"}'
   ```
   Response `status`: `SUCCESS` (green) · `FAILED` (your tests failed — fix and retry) · `OVERLOADED` (box
   busy — wait and retry) · `ERROR` (infra fault) · `REJECTED` (HTTP 422 — your build args aren't on the
   allowlist; drop the offending flag). Parse the JSON (e.g. `jq -r .status`); poll `/merge/status` rather
   than assuming an instant land.
2. **Submit your branch** once it's green (the helper integration-tests it against the current `main` and
   lands it, or bounces it if it conflicts/breaks):
   ```bash
   curl -sX POST 127.0.0.1:8723/merge/submit -H 'Content-Type: application/json' \
     -d '{"branch":"my-feature","module":":app","agentId":"you"}'
   ```
   (`module` is your change's Gradle path, e.g. `:app` — optional but lets the helper route/verify faster.)
3. **Check it landed**:
   ```bash
   curl -s 127.0.0.1:8723/merge/status     # landed went up = merged. bouncedSemantic/Textual or a revert = it didn't; fix and resubmit.
   ```
4. **(Optional) warm up first** so your first build isn't slow:
   ```bash
   curl -sX POST 127.0.0.1:8723/warmup -H 'Content-Type: application/json' \
     -d '{"adapter":"gradle","worktree":"/abs/path/to/your/checkout"}'
   ```

## Rules that keep it sound (do these)
- **Branch-test before you submit.** The helper trusts that a submitted PR already passed its own tests; that
  branch-gating is what keeps `main` green. Submit only after step 1 returns `SUCCESS`.
- **Reuse a stable worktree** (one per agent) across your builds — the executor keeps it warm; a fresh
  worktree per task pays cold-start every time.
- **Back off on `OVERLOADED`** rather than hammering — the bound is protecting the machine from collapse.

## Not a Gradle project?
Set `"adapter"` to your ecosystem: `maven`, `pytest`, `cargo`, `go`, `vitest`, `npm`, `make` (each runs its
conventional test command with no `tasks`), or `exec` to spell out any command in `tasks`. Everything else
(bounded executor, merge workflow) is identical.

## Is something wrong?
`GET /health` shows live capacity + `recentErrorCount` + the last few faults. `GET /metrics` is a Prometheus
scrape target (dashboard in `ops/`). `scripts/arxael logs` prints recent activity in plain English. The full
audit trail is `~/.arxael/events.jsonl`.

## Want the why / the limits / the design?
`docs/ARCHITECTURE.md` (how everything works — components, flows, API, invariants, the headline numbers +
recommended design, and how merging-without-conflicts was designed and measured), `docs/OVERVIEW.md` (the
shape), `docs/SETUP.md` (all knobs).
