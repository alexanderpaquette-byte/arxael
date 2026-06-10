# arxael-dev-kit

> Working name — deliberately language-neutral. This is a *multi-language* tool; the name must never imply one ecosystem (gradle is just the first adapter, not the product).

**Concurrent multi-language test/build orchestration for AI-agent fleets.** One warm, bounded executor that many local coding agents route through — instead of each agent cold-starting its own build daemon in its own isolated sandbox.

> ⚡ **Fresh box, just want it running?** One command: `bash scripts/bootstrap.sh` → verified-running in <10 min. See **[QUICKSTART.md](QUICKSTART.md)**.
> 🤖 **AI agent with zero context?** Read **[AGENTS.md](AGENTS.md)** — the run-it-and-use-it contract.
> 🧑 **Already set up, daily use?** `scripts/arxael up` / `status` / `logs` / `stop`. No jargon. See [docs/SETUP.md](docs/SETUP.md).
> Already running? `curl -s 127.0.0.1:8723/` returns the live, self-describing API.

## The one-sentence pitch

> Everyone else gives each agent its own cold, isolated box — or spreads one build across a cluster. We give the whole fleet **one warm, bounded, shared executor** on a box you own.

## The problem

Run many AI coding agents in parallel and each one kicks builds/tests. Two things break at scale:

- **Cloud CI minutes** get exhausted fast under agent-driven parallel load.
- **Cold-start per agent** — every agent spins its own JVM/build daemon in its own sandbox, multiplying RAM and wall-clock until the box falls over.

The differentiator is **density, not speed**: a single build runs at the toolchain's speed either way. The win is *how many concurrent agents one box sustains before it falls over.*

## Features at a glance

- **Never lands broken code.** Every change is tested *merged onto the live `main`* and lands only if it passes — automatically reverted if not (the merge gate).
- **One warm, shared engine for the whole fleet.** Many agents route through a single bounded executor instead of each cold-starting its own — the density win (the warm bounded executor).
- **Works with your language.** Zero-config adapters for **gradle, gradlew, maven, pytest, cargo, go, vitest, npm, make**, plus a generic **exec** — an agent just names it.
- **Sizes itself to your box.** Learns each build's memory footprint and tunes concurrency up/down on its own — no knobs to set (adaptive auto-sizing).
- **Only re-tests what changed.** Skips doc-only edits and scopes tests to the modules a change actually touched (change-aware scoping).
- **Fast, conflict-free merges.** Auto-routes each change (land-now-verify-async for small, gate-then-land for big) and, on a red batch, blames the right change instead of bouncing everyone (auto-route + culprit attribution).
- **Landings never wait behind branch-tests.** A reserved high-priority lane keeps merges flowing under a flood of agent test runs.
- **Survives crashes.** A journal re-checks any in-flight change after a restart, so a crash never leaves a broken or unverified change on `main` (crash recovery).
- **Self-healing on one box.** Runs as a supervised service that auto-restarts on crash *and* on hang, and reaps leaked build daemons (systemd unit + liveness watchdog).
- **See everything live.** Native **Prometheus** metrics at `/metrics` (no exporter) + a ready **Grafana** dashboard — throughput, time-to-land, reverts, live capacity (in `ops/`).
- **Never wedges the box.** Fails closed under overload (tells a caller to retry rather than collapsing) and bounds output, request size, and disk growth.
- **No re-download storms.** Per-worktree build homes share one read-only dependency cache, so concurrent builds don't re-fetch or lock each other (shared RO dep cache).

## Multi-language

Adapters ship for **gradle** (warm Tooling-API), **gradlew** (a project's own wrapper), **maven**, **pytest**, **cargo**, **go**, **vitest**, **npm**, **make**, plus a generic **exec** for any command. An agent just names the adapter — `{"adapter":"pytest","worktree":"/path"}` runs the conventional test command with zero config, or pass explicit `tasks` to override. Adding an ecosystem is one line behind the `BuildAdapter` SPI; the executor and merge orchestration don't change.

## What you bring vs. what arxael provides

**In plain terms:** arxael is the gatekeeper that checks every change *before* it goes into your project and only lets it in if the checks pass — so one AI agent's broken change never lands and breaks everyone else's work. It runs **your project's own checks**; it doesn't invent them for you.

What's a "check"? At minimum, **does the project still build / run?** — for most stacks, a change that doesn't compile is caught and rejected automatically. On top of that, **any tests your project has**. You don't have to know testing or write them yourself: tell your AI agent *"add a test that proves X works,"* and from then on arxael enforces it on **every** future change, from every agent. That's the loop that ends "my app keeps breaking" — each bug becomes a test, and each test becomes a permanent, automatic guardrail.

> **No tests at all?** arxael still does two useful things — it blocks changes that break the build, and it merges everyone's work without conflicts. It just can't catch a bug that nothing tests for. Adding even a few checks (or asking your agent to) is where it starts catching real regressions.

**arxael provides — the whole gating engine, you don't build it:**
- The **gate**: every change is tested *merged onto the live `main`* and lands only if green, **auto-reverting if it goes red** — plus smart routing, change-scoping (skip doc-only edits), blame-the-right-change attribution, and crash recovery.
- The **warm, shared executor** that runs those checks fast for the whole agent fleet at once.
- **Adapters that already know each stack's test command** — `gradle test` / `:module:test`, `mvn test`, `pytest`, `cargo test`, `go test ./...`, `npm test`, `make test`, the project's own `./gradlew`. Zero-config (an agent just names the adapter), or override per call (`tasks`/`args`) or per deployment (`ARXAEL_<NAME>_CMD`).

**You bring:**
- **Your project** — a git repo with a `main` branch.
- **The checks** — at minimum that it builds; ideally tests (your AI can write them).
- Agents that confirm their change passes **on their own branch** before submitting it; arxael then re-checks it *merged with everyone else's work* before letting it land.

The `scripts/smoke.sh` / `scripts/arxael verify` that run at install time test **arxael itself** — not your project.

## Observability

The daemon exposes **native Prometheus metrics** at `GET /metrics` (no exporter, no extra deps) — throughput (`landed/min`), time-to-land, live concurrency, learned memory footprint, reverts/bounces. Drop-in scrape config + Grafana dashboard in **[ops/](ops/)**. These are the same numbers used to decide whether a change is actually better.

## How it works

**[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** is the authoritative "how everything works" reference —
every component, the request flows (build, merge, adaptive sizing, recovery), the HTTP API, and the safety
invariants. Configure it via [docs/SETUP.md](docs/SETUP.md); the *why* (experiments + numbers) is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); the visual map is [docs/OVERVIEW.md](docs/OVERVIEW.md). What it
deliberately does **not** do — and the trust model it assumes — is in
[docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## Status

Early-stage build, in active development. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how it works and [docs/LIMITATIONS.md](docs/LIMITATIONS.md) for what it does not do yet.

## License

Licensed under the [Apache License 2.0](LICENSE).
