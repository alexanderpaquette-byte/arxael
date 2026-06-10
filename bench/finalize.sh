#!/usr/bin/env bash
# Autonomous result committer. The pipeline writes to bench/out/ (gitignored), so this snapshots
# the STRUCTURED data (every result.json + per-second timeseries.jsonl + event logs + fio/scar/
# iostat — no dark spots; only the redundant binary atop.raw replay is excluded) into the TRACKED
# bench/results/ and PUSHES to GitHub every 15 min. So everything is durable off-box well before
# the 6h instance shutdown, and survives even an instance terminate. Runs as its own service.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(dirname "$HERE")"; cd "$REPO"
LOG="$REPO/bench/out/campaign/finalize.log"; mkdir -p "$(dirname "$LOG")"
say(){ echo "[$(date -u +%H:%M:%SZ)] $*" | tee -a "$LOG"; }
GID=(-c user.name="Alexander Paquette" -c user.email="alexanderpaquette@gmail.com")

snapshot() {
  mkdir -p bench/results/campaign
  rsync -a --exclude='atop.raw' bench/out/campaign/ bench/results/campaign/ 2>/dev/null || true
  { for f in bench/results/campaign/p*.jsonl bench/results/campaign/disk/*.jsonl; do
      [ -s "$f" ] && { echo "### $(basename "$f")"; python3 bench/analyze.py "$f" 2>/dev/null; echo; }
    done; } > bench/results/SUMMARY.txt 2>/dev/null || true
  git add bench/results >/dev/null 2>&1 || true
  if ! git diff --cached --quiet 2>/dev/null; then
    git "${GID[@]}" commit -q -m "data: results snapshot $(date -u +%Y-%m-%dT%H:%MZ)" \
      -m "Co-Authored-By: Claude <noreply@anthropic.com>" 2>>"$LOG" || true
    git pull --rebase --autostash -q origin main 2>>"$LOG" || true
    git push -q origin main 2>>"$LOG" && say "pushed snapshot" || say "push FAILED (will retry next cycle)"
  fi
}

say "finalizer up — snapshot+push every 15m until arxremaining completes"
while systemctl is-active --quiet arxremaining; do
  snapshot
  sleep 900
done
say "pipeline done — final snapshot"
snapshot
date -u +"campaign completed %Y-%m-%dT%H:%M:%SZ" > bench/results/DONE.txt
git add bench/results/DONE.txt >/dev/null 2>&1 || true
git "${GID[@]}" commit -q -m "data: campaign COMPLETE — all phases logged" \
  -m "Co-Authored-By: Claude <noreply@anthropic.com>" 2>>"$LOG" || true
git pull --rebase --autostash -q origin main 2>>"$LOG" || true
git push -q origin main 2>>"$LOG" && say "FINAL push done" || say "FINAL push FAILED"
