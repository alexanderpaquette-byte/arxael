# Changelog

All notable changes to arxael-dev-kit. Versions follow [SemVer](https://semver.org/) (pre-1.0: minor =
notable change, patch = fix).

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
