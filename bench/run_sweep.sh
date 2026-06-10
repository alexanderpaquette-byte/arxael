#!/usr/bin/env bash
# Deliverable #1 sweep — both arms x agent-ramp x cores, clean room between cells.
#
# Produces one results.jsonl (one line per cell) for analyze.py. Each cell also leaves a full
# observability bundle under out/<profile>/<arm>-c<cores>-a<agents>/ (timeseries, atop replay,
# pidstat, daemon + event logs). Configure via env; sensible defaults below.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(dirname "$HERE")"

ARMS="${ARMS:-warm container}"
AGENTS="${AGENTS:-1 2 4 8 16 32 48 64}"
CORES="${CORES:-32}"
PROFILE="${PROFILE:-test}"
CLASSES="${CLASSES:-150}"
WARMUP="${WARMUP:-20}"
WINDOW="${WINDOW:-60}"
MEM_GB="${MEM_GB:-0}"
FIXTURE="${FIXTURE:-/tmp/bench-fixture}"
OUT="${OUT:-$REPO/bench/out/$PROFILE}"
RESULTS="$OUT/results.jsonl"

mkdir -p "$OUT"
: > "$RESULTS"

echo "[sweep] generating fixture ($CLASSES classes)"
python3 "$HERE/fixtures/gen_fixture.py" "$FIXTURE" --classes "$CLASSES"

clean_room() {
  # No cross-cell contamination: kill stray gradle daemons + leftover bench containers.
  pkill -f GradleDaemon 2>/dev/null || true
  sudo docker ps -aq --filter "name=arxbench_" | xargs -r sudo docker rm -f >/dev/null 2>&1 || true
  sync
  sleep 2
}

for C in $CORES; do
  for A in $AGENTS; do
    for ARM in $ARMS; do
      echo "[sweep] arm=$ARM cores=$C agents=$A profile=$PROFILE"
      clean_room
      RUN_DIR="$OUT/${ARM}-c${C}-a${A}"
      python3 "$HERE/bench.py" --arm "$ARM" --agents "$A" --cores "$C" --profile "$PROFILE" \
        --classes "$CLASSES" --warmup "$WARMUP" --window "$WINDOW" --mem-gb "$MEM_GB" \
        --fixture "$FIXTURE" --run-dir "$RUN_DIR" --out "$RESULTS" \
        >/dev/null 2>>"$OUT/sweep.err" || echo "[sweep] cell FAILED (see sweep.err)"
    done
  done
done

clean_room
echo "[sweep] done -> $RESULTS"
python3 "$HERE/analyze.py" "$RESULTS"
