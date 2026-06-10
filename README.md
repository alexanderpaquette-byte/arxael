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

## Multi-language

Adapters ship for **gradle** (warm Tooling-API), **gradlew** (a project's own wrapper), **maven**, **pytest**, **cargo**, **go**, **vitest**, **npm**, **make**, plus a generic **exec** for any command. An agent just names the adapter — `{"adapter":"pytest","worktree":"/path"}` runs the conventional test command with zero config, or pass explicit `tasks` to override. Adding an ecosystem is one line behind the `BuildAdapter` SPI; the executor and merge orchestration don't change.

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
