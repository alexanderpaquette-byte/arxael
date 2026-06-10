#!/usr/bin/env bash
# Phase 5 — agentsPerCore tuning sweep (runs AFTER the main campaign finishes).
#
# Finding from Phase 2 cores=32: with agentsPerCore=1 the warm arm caps at 32 concurrent and leaves
# throughput on the table vs the unbounded container arm on a fat box. This sweep raises the bound
# (brief: "empirically-tuned ~agents-per-core, NOT fixed 1") to find where warm MATCHES container
# goodput while keeping RAM bounded (no collapse). cores=32, uncapped, fixed high agent load.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$(dirname "$HERE")/bench/out/campaign"
LOG="$OUT/phase5.log"
R="$OUT/p5-tuning.jsonl"
mkdir -p "$OUT"

say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

clean_room() {
  pkill -f GradleDaemon 2>/dev/null || true
  sudo docker ps -aq --filter "name=arxbench_" | xargs -r sudo docker rm -f >/dev/null 2>&1 || true
  sudo systemctl stop arxbench.slice 2>/dev/null || true
  sync; sleep 2
}

# 1. Wait for the main campaign to finish (poll; cheap).
say "Phase 5 waiting for arxcampaign to finish..."
while systemctl is-active --quiet arxcampaign; do sleep 30; done
say "campaign done — starting agentsPerCore tuning sweep"

python3 "$HERE/fixtures/gen_fixture.py" /tmp/bench-test --classes 150 >>"$LOG" 2>&1
: > "$R"

CORES=32; AGENTS=96; WARMUP=20; WINDOW=45

run() {  # arm extra...
  clean_room
  python3 "$HERE/bench.py" --arm "$1" --agents "$AGENTS" --cores "$CORES" --profile test \
    --classes 150 --warmup "$WARMUP" --window "$WINDOW" --fixture /tmp/bench-test \
    --run-dir "$OUT/tuning-$2" --out "$R" "${@:3}" >>"$LOG" 2>&1 || say "  !! cell FAILED ($2)"
}

# warm at increasing agentsPerCore
for APC in 1 1.5 2 3; do
  say "  warm cores=$CORES agents=$AGENTS agentsPerCore=$APC"
  run warm "warm-apc$APC" --agents-per-core "$APC"
done
# container baseline (unbounded) at the same load
say "  container baseline cores=$CORES agents=$AGENTS (unbounded)"
run container "container"

clean_room
say "=== Phase 5 done — summary ==="
python3 - "$R" <<'PY' | tee -a "$LOG"
import json,sys
rows=[json.loads(l) for l in open(sys.argv[1]) if l.strip()]
print(f"  cores=32 agents=96 uncapped — does tuning the bound let warm match container?\n")
print(f"  {'arm/apc':<16} {'maxConc':>7} {'good/min':>8} {'wedge%':>6} {'RAM_GB':>7} {'gradleD':>7} {'p95s':>6} {'load1':>6}")
def label(r):
    return f"warm apc={r.get('agents_per_core')}" if r['arm']=='warm' else "container(unbnd)"
for r in sorted(rows,key=lambda r:(r['arm'],r.get('agents_per_core',0))):
    mc = round(32*r.get('agents_per_core',0)) if r['arm']=='warm' and r.get('agents_per_core',0)>0 else ('—' if r['arm']=='container' else 32)
    print(f"  {label(r):<16} {str(mc):>7} {r['goodput_per_min']:>8.0f} {100*r.get('wedge_rate',0):>5.0f}% "
          f"{r.get('peak_mem_used_mb',0)/1024:>7.1f} {r.get('peak_gradle_daemons',0):>7} "
          f"{r.get('p95_latency_s',0):>6.1f} {r.get('peak_load1',0):>6.0f}")
PY
