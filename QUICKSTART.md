# QUICKSTART — fresh box to running in under 10 minutes, one command

The whole thing, from a bare machine, is a single command. No prior context, no configuration.

## If you already have this repo
```bash
bash scripts/bootstrap.sh
```
That's the entire setup. It will:
1. install any missing base tools (git, curl, unzip, python3),
2. install JDK 21 and a pinned Gradle,
3. build the helper,
4. start it, and
5. **verify it's actually answering** — then print `✓ READY in NmSSs` and your next step.

It's idempotent (safe to re-run) and self-cleaning. On a warm box it finishes in seconds; on a truly fresh
box the one-time JDK + Gradle downloads and first build are the only slow part (a few minutes, comfortably
under ten). **Measured:** a clean `ubuntu:24.04` container (nothing pre-installed) reaches `✓ READY` in
**~70 seconds** — validated by `bench/install_container.sh`, which also runs `arxael verify` (unit tests +
acceptance smoke + multi-language) to prove it actually works, not just answers.

## From absolute zero (no repo yet)
```bash
# 1. get git if the box doesn't have it (Debian/Ubuntu shown; use your package manager)
command -v git || sudo apt-get update && sudo apt-get install -y git
# 2. clone + bootstrap
git clone https://github.com/alexanderpaquette-byte/arxael-dev-kit.git
cd arxael-dev-kit && bash scripts/bootstrap.sh
```

## Did it work?
`bootstrap.sh` ends by running the check for you. Any time after:
```bash
scripts/arxael status
# ✓ On — running up to N builds at once on N cores.
# ✓ No problems.
```

## Now point it at YOUR project
The bootstrap just starts the shared helper. To put it to work, go into a project (a git repo with a `main`
branch) and connect it:
```bash
cd /path/to/your/project
/path/to/arxael-dev-kit/scripts/arxael up      # "✓ Connected your-project (N modules). Agents can now submit changes."
```
Turn it off any time with `scripts/arxael stop` (it cleans up after itself).

## What runs your tests — and what if I don't have any?
arxael is the gatekeeper: it checks every change *before* it lands and only lets it in if the checks pass, so
one agent's broken change can't break everyone else. It runs **your project's own checks** — it doesn't write
them for you. At minimum that's *"does it still build?"* (caught automatically for most stacks); beyond that,
any tests you have.

**No tests yet?** It still blocks broken builds and merges everyone's work cleanly — and the moment you, or
your AI (*"add a test that proves X works"*), add a check, arxael enforces it on **every** future change from
**every** agent, automatically. That's how "my app keeps breaking" stops: each bug becomes a test, each test
becomes a permanent guardrail.

It auto-detects the command for your stack (`gradle test`, `mvn test`, `pytest`, `cargo test`, `go test`,
`npm test`, …) — an agent just names the adapter. (The `verify`/smoke step above tests arxael itself, not
your project.)

## AI agents
Read [AGENTS.md](AGENTS.md), or — if the helper is already running — ask it to describe itself:
```bash
curl -s 127.0.0.1:$(cat ~/.arxael/port)/      # self-describing JSON: what it is + every endpoint + request shapes
```

## What's underneath
[docs/SETUP.md](docs/SETUP.md) (every knob), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (the numbers + design),
[docs/OVERVIEW.md](docs/OVERVIEW.md) (the shape). But you don't need any of it to get running — just the one
command above.
