# Changelog

All notable changes to arxael-dev-kit. Versions follow [SemVer](https://semver.org/) (pre-1.0: minor =
notable change, patch = fix).

## [1.0.1] — 2026-06-10

Public-facing polish (docs only; no behavior change):
- The public README/OVERVIEW title now reads **arxael** (matches the public repo); the internal "working
  name" dev-note is dropped from the public release.
- The top-of-README tagline now leads with **bring-your-own-gates** so the "runs your existing checks, does
  not replace your tests/CI" positioning is unmissable above the fold.

## [1.0.0] — 2026-06-10

First stable release. The API and behavior are stable; future changes follow SemVer.

**Positioning (clarified, not changed):** arxael is a **local orchestration layer for AI coding agents** —
a warm, bounded shared executor plus a merge gate that keeps `main` green under parallel agent work. It is
**not** a testing tool or a CI replacement: you **bring your own gates** (tests, checks, build), and arxael
runs the checks *you already trust*, warm and merge-safe under controlled concurrency.

**How it earned 1.0** — five adversarial review waves (26 verified bug fixes; the final waves found no
high/medium issues, the convergence signal), an audit of those fixes (no regressions), and three green
empirical pillars on the hardened build:
- `scripts/arxael verify` — unit + acceptance smoke + 5 real-toolchain multi-language invocations.
- **Crash recovery** — SIGKILL the daemon mid-merge, restart: all in-flight PRs re-gated, `main` green, 0 reverts.
- **Real-world OSS** — 10 agents landing real PRs through google/gson's *own* Maven suite: every good PR
  landed, every broken one caught, `main` green, 0 reverts.

Also in 1.0: fixed the public clone URL (the published QUICKSTART pointed at the private repo), and sharpened
the README positioning to the "bring your own gates" framing.

## [0.9.0] — 2026-06-10

Fifth wave: a final pre-1.0 "release-blocker" sweep of the corners earlier waves under-covered — the
install/ops scripts, the systemd unit, API/protocol stability, and doc-vs-reality. It confirmed the hard
guarantees are 1.0-grade (`main` never left red, no green PR lost, repo-scoped journal recovery, bounded
growth, no FD/thread leaks, protocol stability) and found one real blocker, now fixed:

- **systemd crash-loop limiter was inert:** `StartLimitIntervalSec`/`StartLimitBurst` were in `[Service]`,
  which systemd ignores (verified via `systemd-analyze`) — so a deterministic startup failure (e.g. the port
  already in use) would restart every 2s forever. Moved to `[Unit]`; now >5 starts in 60s fails the unit
  instead of thrashing.
- The daemon now exits with a **clear message** ("cannot bind 127.0.0.1:&lt;port&gt; …") on a bind failure,
  instead of an opaque looping stack trace.
- `install-service.sh` parses the advertised `--user` flag, and the liveness probe reads `ARXAEL_PORT` from
  the same env source as the daemon (a port change can't desync the probe and restart a healthy daemon).
- README license set to Apache-2.0 (was "TBD").

## [0.8.0] — 2026-06-10

Fourth review wave: an audit of the prior three waves' own fixes (no high/medium regressions found) plus a
fresh-eyes pass on the under-covered subsystems. The wave found no HIGH/MED issues — the convergence signal
toward v1.0. The LOW items it surfaced, fixed here:

- Reverted the v0.7.0 `waitingCount` change: the two permit-lane queues hold disjoint sets of threads, so the
  SUM is the correct count of distinct waiters (the earlier "double-count" reasoning was wrong; `maxOf`
  under-counted the governor's demand signal).
- Recovered PRs no longer pollute the time-to-land p50: a PR reconstructed from the journal carries a
  `submittedAtNanos` from a prior process (nanoTime has no cross-restart epoch), so it's excluded from the metric.
- The PR journal is now repo-scoped, so a re-register to a *different* project can't replay the previous
  project's branch names (same repo across restarts still recovers correctly).

## [0.7.0] — 2026-06-10

Third adversarial review wave (three reviewers: validator/parser fuzzing, HTTP/adapter robustness, and
whole-system integration races). Each finding was verified against the code; the over-stated ones were
refuted (a gate-cache "clobber" the TTL + create-mtime guard already prevents; an "unsound routing" probe
claim — the probe is fail-safe). 8 fixes, each with a test.

### Robustness / fail-closed config
- `BoxConfig` no longer crashes at startup on a non-finite `ARXAEL_AGENTS_PER_CORE` (NaN/Infinity threw in
  `roundToInt`); `memBound` is clamped so a huge-RAM/tiny-footprint box can't saturate it to `Int.MAX` and
  disable the OOM guard; negative/zero numeric env vars fall back to sane defaults instead of a degenerate box.
- `/invoke` arg tokens are length-bounded; `/merge/submit` also rejects `..` and leading-`/` refs.
- `MetricsRenderer` sanitizes metric names to the Prometheus charset (a stray key can't emit a malformed or
  injected exposition line).

### Stability
- HTTP request read-timeout (`maxReqTime`) bounds slow/partial bodies so a stalled caller can't park a worker
  and starve `/health` (response time deliberately unbounded — builds run in the handler and take minutes).
- Merge-gate worktree servers are pinned, so an agent flood can't LRU-evict them and leave landings cold.
- The adaptive demand signal no longer double-counts the two permit lanes (less needless resize churn).

### Ergonomics
- `ARXAEL_<NAME>_CMD` accepts a JSON array for commands whose path or args contain spaces.

## [0.6.0] — 2026-06-10

Hardening release. 15 bugs found and fixed across two adversarial review waves (nine independent reviewers
swept the merge, executor, autosize, protocol, adapter, persistence, Gradle-cache, routing/attribution, and
lifecycle subsystems). Every finding was verified against the code before fixing — two over-stated findings
were refuted and left alone — and each fix carries a regression test. 167 tests; `scripts/arxael verify`
green (unit + smoke + multi-language).

### Soundness (a wrongly-landed or lost change is the worst outcome for a test machine)
- **Crash recovery survives branch reuse.** The PR journal is now order-sensitive: a branch that is
  submitted, bounced, then resubmitted-and-landed is recovered after a crash instead of being silently
  dropped (which would strand an unverified change on `main`).
- **Batched merge can't land a red change via undeclared coupling.** When a red batch is attributed and the
  remainder is re-tested, the gate now covers at least the modules that were red — so a break carried across
  an undeclared module boundary can't slip through a narrowed re-test while an innocent PR is bounced.
- **Culprit attribution ignores failure strings echoed in test output.** The `Task … FAILED` /
  `Execution failed for task …` patterns are anchored to line start (Gradle emits them at column 0), so a
  test that logs or asserts on such a string can't bounce an innocent PR.
- **Merge gate runs with `--continue`** so a multi-module gate reports every failing module in one pass.

### Stability and resource bounds (a long-running daemon must not degrade)
- **ProcExec kills the whole process tree on timeout** — a grandchild holding stdout open no longer wedges
  output draining; signal-kills (SIGKILL/OOM) are now reported distinctly from ordinary test failures.
- **Bounded disk:** the event log rotates at 64 MB; per-worktree build caches are reclaimed on eviction
  (TTL-guarded so an in-flight build is never deleted out from under it).
- **The shared read-only dependency cache is never written in place** — it is warmed into a separate seed
  home and then published by atomic copy, so a concurrent build can't read a half-written artifact.
- **Clean shutdown:** in-flight merge gates drain before the executor tears down; no warm server (or its
  Gradle daemon) leaks past shutdown; the daemon reaper matches the state directory on a path boundary, so it
  can never reap an unrelated sibling instance's daemons.

### Defensive
- **`/merge/submit` validates** branch/module/agentId — rejects leading-dash (git-option injection) and
  tab/newline (PR-journal record forgery).
- Atomic file writes fsync the temp file before the rename; the adaptive memory baseline is an EWMA that a
  single transient reading can't permanently poison; the executor's concurrency resize is atomic.

## [0.5.0] — 2026-06-09

Initial public release: the warm bounded executor, multi-language adapters, the auto-routing merge
orchestrator, adaptive auto-sizing, native Prometheus/Grafana, and the validation harnesses.
