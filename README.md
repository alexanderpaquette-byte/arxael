# Arxael

### Land a fleet of AI agents on a `main` that never breaks.

**Arxael is the merge layer for agent fleets.** It runs *your* real tests on a **warm merge of live `main`**, lands what passes — in parallel — and **auto-reverts what fails**, all on a box you own. Many agents, one `main`, always green.

```bash
npm install -g arxael    # the launcher (Java 21+; ~5 MB engine fetched on first run)
arxael up                # in your repo — auto-detects your test gate and starts
```

---

## Locally green ≠ green on `main`

Point five AI coding agents at one repo and each one passes **on its own branch**. Two things then break:

- **`main` breaks anyway** — nothing tested their changes *together, merged onto the real `main`*.
- **the box wedges** — every agent cold-starts its own build daemon, and the parallel load piles up RAM and lock contention until the machine deadlocks.

Arxael fixes both:

- **`main` stays green.** Every change is tested **merged onto the *current* `main`** — not "CI passed on a stale branch" — and lands only if it's green. Conflict-free is not the same as working: a real-test gate on the **actual merge of live `main`** is the thing branch checks, merge queues (which test a *predicted* main on cold CI), and git-only orchestrators don't give you.
- **The box doesn't wedge.** The whole fleet funnels through **one warm, bounded, fail-closed executor** instead of a cold-start daemon per agent — so heavy parallel load **queues**, it doesn't multiply daemons until the box deadlocks. Parallelize as hard as you want; it stays up.

## Proven on real repos

This isn't a demo on a toy repo. Arxael has been run hard against real open-source projects — each gated by **its own** test suite, through the real merge orchestrator, in isolated containers — and the numbers held:

> **89 / 89** good PRs landed · **every** bad PR caught (4/4) · **0** reverts reached `main` · `main` stayed green
> — [google/gson](https://github.com/google/gson) (its Maven suite) + [ben-manes/caffeine](https://github.com/ben-manes/caffeine) (its Gradle wrapper).

`main` is never *knowingly* broken: the batched path never lands a red merge; an optimistic land auto-reverts the instant its scoped gate goes red. The one honest residual — undeclared cross-module coupling — is documented in **[Limits & trust](docs/LIMITATIONS.md)**.

## How it works — three things

1. **A real-test gate on live `main`.** Every PR is merged onto the *current* `main` and run through your project's actual checks. Green lands; red doesn't — ever.
2. **Auto-revert that names the culprit.** A change that breaks `main` is reverted automatically; when a *batch* goes red, it blames the change that actually broke it instead of bouncing everyone.
3. **One warm, shared, self-tuning executor.** The whole fleet routes through a single bounded build/test engine — always warm, never cold-starting — so the box sustains *many* agents working in parallel at full toolchain speed, instead of melting under cold-start sprawl. It learns each build's footprint, sizes itself to your hardware, and routes merges (batch vs. optimistic) by live load. **No knobs to set.**

[Watch one PR land in ~2 minutes →](docs/FIRST-LAND.md) · [Full architecture →](docs/ARCHITECTURE.md)

## Built to run unattended

The reliability work *is* the product — Arxael is engineered to be left running, not babysat:

- **Crash-durable & self-healing.** A journal re-checks every in-flight change after a restart, so a crash can't strand a broken or unverified change on `main`. It runs as a supervised service that auto-restarts on crash *and* on hang, and reaps leaked build daemons.
- **Fails closed, never wedges.** Under overload it tells a caller to retry instead of collapsing; output, request size, and disk growth are all bounded. It degrades — it doesn't fall over.
- **Fully observable out of the box.** Native Prometheus `/metrics` (no exporter) plus a ready Grafana dashboard: throughput, time-to-land, reverts, live capacity — the same numbers it uses to prove a change is actually better.
- **Only does the work that matters.** Skips doc-only edits, scopes tests to the modules a change touched, and shares one read-only dependency cache so concurrent builds never re-download or lock each other.

## Bring your own gates

Arxael doesn't replace your tests or CI — it runs the checks **you already trust**, warm and merge-safe under controlled concurrency. The comparison isn't Arxael-vs-your-CI; it's *agents each hammering build/test independently* vs. *one layer coordinating them on the actual merge.*

The minimum check is **"does it still build?"** — most stacks reject a change that doesn't compile, automatically. On top of that, any tests your project has. No tests yet? Tell your agent *"add a test that proves X works"* and Arxael enforces it on **every** future change, from every agent. Each bug becomes a test; each test becomes a permanent guardrail.

## Works with your language

Zero-config adapters for **gradle**, **gradlew**, **maven**, **pytest**, **cargo**, **go**, **vitest**, **npm**, **make**, plus a generic **exec** for any command. An agent just names the adapter — `{"adapter":"pytest","worktree":"/path"}` runs the conventional test command — or passes explicit `tasks` to override. Python/Rust/Node/Go users run completely gradle-free; adding an ecosystem is one line behind the adapter SPI.

## What you bring

- A git repo with a `main` branch.
- The checks — at minimum that it builds; ideally tests (your agent can write them).
- Agents that pass **on their own branch** before submitting; Arxael then re-checks the change *merged with everyone else's work* before it lands.

## Limits & trust — read this

Arxael does one thing extremely well, and is upfront about its edges — the candor is deliberate. It's built for a **trusted fleet on a box you own**: loopback-only, with **no isolation *between* agents** (they share one executor and one `main`). That's the precondition, not a multi-tenant sandbox — and stating it plainly is the point. It's a **single box** by design (no HA or horizontal scale), which is exactly what makes the warm-executor density win possible — and it's crash-durable and self-healing so the single box stays up. The performance and soundness results are benchmark-proven on 16- and 32-core machines and the real-OSS runs above.

**[docs/LIMITATIONS.md](docs/LIMITATIONS.md)** lays out exactly what it does *not* do, the trust model, and a plain *when-to-use / when-not* table. Read it before you trust `main` to it.

## Install

```bash
npm install -g arxael      # the launcher  (or `bash scripts/bootstrap.sh` to build from source)
arxael up                  # in your repo — auto-detects your test gate, starts the daemon
```

- **Linux & macOS.** Native Windows isn't supported — use **WSL2** (the daemon runs fine under it).
- **Java 21+** is required (the engine is a JVM service). If it's missing, `arxael up` prints the exact install command for your OS and stops — it won't half-start.
- **Gradle is optional** — only to build from source or gate *Gradle* projects.
- The npm package is a thin **launcher**; on first run it fetches the sha256-verified ~5 MB JVM **engine** from the latest GitHub release (cached in `~/.arxael`, auto-tracking the latest). Pin one with `ARXAEL_ENGINE_VERSION`; install offline from `ARXAEL_CORE_TARBALL`; `arxael upgrade` pulls a newer engine. Details: **[cli/README.md](cli/README.md)**.
- Merge risk posture: `ARXAEL_MERGE_MODE=conservative | balanced | fast` (default `balanced`, self-tuning).

## See it · use it

| You are… | Go here |
|---|---|
| 👀 curious — *show me it work* | **[docs/FIRST-LAND.md](docs/FIRST-LAND.md)** — one PR lands on a green `main` in ~2 min |
| ⚡ on a fresh box / building from source | `bash scripts/bootstrap.sh` → **[QUICKSTART.md](QUICKSTART.md)** |
| 🤖 an AI agent with zero context | **[AGENTS.md](AGENTS.md)** — the run-it-and-use-it contract |
| 🧑 set up, daily use | `arxael up` / `status` / `logs` / `stop` → **[docs/SETUP.md](docs/SETUP.md)** |
| 📊 want it observable | native Prometheus `/metrics` + a ready Grafana dashboard → **[ops/](ops/)** |
| 🏗 want the whole design | **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** · visual map **[docs/OVERVIEW.md](docs/OVERVIEW.md)** |

Already running? `curl -s 127.0.0.1:8723/` returns the live, self-describing API.

## License

[Apache License 2.0](LICENSE).
