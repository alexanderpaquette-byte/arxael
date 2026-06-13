# arxael

**Run many AI coding agents on one box without breaking `main`.** One warm, bounded, shared build/test
executor for your whole agent fleet, plus a merge gate that lands their PRs onto a shared `main` and
auto-reverts anything that goes red. A bring-your-own-gates orchestration layer — it runs *your project's*
existing checks (gradle, maven, pytest, cargo, go, vitest, npm, make, exec).

## Install

```bash
npm install -g arxael
```

That installs the `arxael` command. The first time you run it, it fetches the engine (a ~5 MB JVM
distribution) from the latest GitHub release, verifies its checksum, and caches it under `~/.arxael`.

**Requirement: Java 21+** (the engine is a JVM service). If it's missing, `arxael` tells you exactly how to
install it for your OS. Gradle is **not** required unless you build *gradle* projects — Python/Rust/Node/Go/etc.
work with no extra tooling.

## Use

```bash
cd your-repo
arxael up          # start the daemon + connect this repo (one-time hub setup)
arxael status      # health, capacity, merge throughput
arxael upgrade     # pull the latest engine release
arxael stop        # stop the daemon
arxael --version   # CLI version + the engine version in use
```

`arxael up` **auto-detects your project's test gate** from the directory (Cargo.toml → cargo, go.mod → go,
`pyproject.toml`/test files → pytest, package.json → npm, build.gradle → gradle, pom.xml → maven) and prints
which it chose. Override any time with `ARXAEL_MERGE_GATE_ADAPTER=<adapter>`.

Agents submit work over a tiny local HTTP API (`127.0.0.1`, loopback only) — `arxael status` shows the port.
Watch one PR land on a green `main`, then explore from there.

## How versioning works

This launcher is intentionally **decoupled** from the fast-moving product version. It resolves the **latest
release that ships an engine artifact** and caches it, so the project can publish improvements continuously
without you reinstalling — `arxael upgrade` (or `npm install -g arxael@latest` for the launcher itself) pulls
the newest engine. Pin a specific engine with `ARXAEL_ENGINE_VERSION=1.2.3` for reproducibility.

**Knowing what you're running + staying current.** `arxael version` shows the CLI / engine / running-daemon
versions and **warns if the warm daemon is older than the installed engine** (restart to adopt). An
**interactive-only, once/day** check tells you when a newer release exists — it **never auto-updates** (so a
pinned, vetted build stays put), is **off for agents / non-TTY / `CI`** (nothing ever beacons on the agent
path), sends no code or telemetry (just a GET to the public releases API), and is opt-out with
`ARXAEL_NO_UPDATE_CHECK=1` (or `DO_NOT_TRACK`). Updating is always a deliberate `arxael upgrade`.

## Notes

- **Platforms:** Linux and macOS. (Windows: run under WSL for now.)
- **Source build:** `arxael verify` / `arxael bench` build from source — clone
  [the repo](https://github.com/alexanderpaquette-byte/arxael) for those.
- **Offline / air-gapped:** point `ARXAEL_CORE_TARBALL=/path/to/arxael-core-*.tar.gz` at a local engine archive.

Apache-2.0 · [github.com/alexanderpaquette-byte/arxael](https://github.com/alexanderpaquette-byte/arxael)
