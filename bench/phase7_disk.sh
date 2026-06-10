#!/usr/bin/env bash
# Phase 7 — DETAILED disk/IO study. Runs LAST, on the (now-optimized) io2 volume. One-shot: the
# io2 disk is expensive and will be reverted, so this captures everything in a single pass.
#
# Steps (each independent; partial failure still leaves prior data):
#   0. probe       - fio confirms the volume's real envelope (is it actually ~40K IOPS?)
#   1. envelope    - full fio characterization: rand/seq x read/write/mixed, IOPS+BW+latency
#   2. knob-check  - fio under cgroup io.max at 16K/8K/4K -> proves the throttle delivers the target
#   3. collapse    - container disk-collapse sweep: ramp agents at io ceilings {none,16K,8K,4K},
#                    warm run unthrottled as reference -> where disk becomes the first wall
#   4. write-amp   - warm (1 shared cache) vs container (N caches): IOPS + MB written per task
#   5. scar #8375  - shared writable build-cache across worktrees (isolation OFF) vs per-worktree ON
#                    -> reproduce the LockTimeout wedge the fix prevents
#   6. P1-revalid  - re-run the P1 capped collapse on fast disk; did the collapse point move vs gp3?
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"
OUT="$REPO/bench/out/campaign/disk"; LOG="$OUT/phase7.log"; mkdir -p "$OUT/fio"
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

DEV=nvme0n1; DEVP=/dev/nvme0n1
FIOF="$HOME/fio-test.dat"          # on the io2 root volume
command -v fio >/dev/null 2>&1 || { say "installing fio"; sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq fio >>"$LOG" 2>&1; }

fio_job() {  # name rw bs extra...
  local name=$1 rw=$2 bs=$3; shift 3
  fio --name="$name" --filename="$FIOF" --size=4G --direct=1 --ioengine=libaio \
      --rw="$rw" --bs="$bs" --iodepth=64 --runtime=30 --time_based --group_reporting \
      --output-format=json "$@" 2>>"$LOG"
}

# ---- 0+1. envelope at full io2 ----
say "STEP 1: fio envelope at full io2"
for spec in "randread randread 4k" "randwrite randwrite 4k" "randrw70 randrw 4k --rwmixread=70" \
            "seqread read 1m" "seqwrite write 1m"; do
  set -- $spec; nm=$1; rw=$2; bs=$3; shift 3
  say "  fio $nm"; fio_job "$nm" "$rw" "$bs" "$@" > "$OUT/fio/$nm.json" || say "    fio $nm failed"
done
python3 - "$OUT/fio" <<'PY' | tee -a "$LOG"
import json,glob,os,sys
for f in sorted(glob.glob(sys.argv[1]+"/*.json")):
    try:
        d=json.load(open(f))["jobs"][0]
        r,w=d["read"],d["write"]
        print(f"  {os.path.basename(f)[:-5]:<10} read {r['iops']:>8.0f} iops {r['bw']/1024:>7.0f} MB/s p95={r['clat_ns'].get('percentile',{}).get('95.000000',0)/1e6:>6.2f}ms | "
              f"write {w['iops']:>8.0f} iops {w['bw']/1024:>7.0f} MB/s")
    except Exception as e: print(f"  {f}: parse err {e}")
PY

# ---- 2. knob-check: does cgroup io.max deliver the target? ----
say "STEP 2: io.max knob validation (fio under systemd scope)"
for LIM in 16000 8000 4000; do
  say "  throttle target ${LIM} iops"
  ACH=$(sudo systemd-run --scope --quiet -p IOWriteIOPSMax="$DEVP $LIM" -p IOReadIOPSMax="$DEVP $LIM" \
        fio --name=thr --filename="$FIOF" --size=2G --direct=1 --ioengine=libaio --rw=randwrite \
        --bs=4k --iodepth=64 --runtime=20 --time_based --group_reporting --output-format=json 2>>"$LOG" \
        | python3 -c "import sys,json;print(int(json.load(sys.stdin)['jobs'][0]['write']['iops']))" 2>/dev/null || echo "?")
  say "    target=${LIM} achieved=${ACH} iops"
  echo "{\"target\":$LIM,\"achieved\":\"$ACH\"}" >> "$OUT/knob-check.jsonl"
done

cell() {  # results arm agents extra...
  local res=$1 arm=$2 ag=$3; shift 3
  guard_disk || return 0
  clean_room
  local rd="$OUT/c-$arm-a$ag-$(echo "$*" | tr ' /=' '___')"
  say "  $arm agents=$ag $*"
  python3 "$HERE/bench.py" --arm "$arm" --agents "$ag" --cores 16 \
    --fixture /tmp/bench-coverage --run-dir "$rd" --out "$res" "$@" >>"$LOG" 2>&1 || say "    !! FAILED"
  trim_bundle "$rd"
}
python3 "$HERE/fixtures/gen_fixture.py" /tmp/bench-coverage --classes 150 >>"$LOG" 2>&1

# global iostat logger (await / aqu-sz that the /proc sampler doesn't compute)
iostat -dx "$DEV" 2 > "$OUT/iostat.log" 2>/dev/null &
IOSTAT_PID=$!

# ---- 3. container disk-collapse sweep across io ceilings; warm unthrottled reference ----
say "STEP 3: disk-collapse sweep (coverage, container throttled vs warm native)"
R="$OUT/p7-collapse.jsonl"; : >"$R"
for IOPS in 0 16000 8000 4000; do
  for A in 4 8 16 24 32; do
    cell "$R" container "$A" --profile coverage --io-iops "$IOPS" --warmup 20 --window 45
  done
done
for A in 4 8 16 24 32; do cell "$R" warm "$A" --profile coverage --warmup 20 --window 45; done

# ---- 4. write-amplification: warm (1 cache) vs container (N caches) ----
say "STEP 4: write-amplification (coverage, agents=16, uncapped)"
R="$OUT/p7-writeamp.jsonl"; : >"$R"
cell "$R" warm 16 --profile coverage --warmup 20 --window 60
cell "$R" container 16 --profile coverage --warmup 20 --window 60

kill "$IOSTAT_PID" 2>/dev/null || true

# ---- 5. #8375 scar: shared writable build cache vs per-worktree ----
say "STEP 5: Gradle #8375 validation (shared cache wedge vs per-worktree isolation)"
bash "$HERE/scar8375.sh" "$OUT" >>"$LOG" 2>&1 || say "  scar8375 step failed"

# ---- 6. P1 re-validation on fast disk ----
say "STEP 6: P1 capped-collapse re-validation on io2 (compare collapse point vs gp3)"
python3 "$HERE/fixtures/gen_fixture.py" /tmp/bench-test --classes 150 >>"$LOG" 2>&1
R="$OUT/p7-p1revalid.jsonl"; : >"$R"
for A in 8 16 24 32 40; do
  guard_disk || break
  clean_room
  for ARM in warm container; do
    rd="$OUT/revalid-$ARM-a$A"
    python3 "$HERE/bench.py" --arm "$ARM" --agents "$A" --cores 16 --mem-gb 24 --profile test \
      --fixture /tmp/bench-test --run-dir "$rd" --out "$R" --warmup 20 --window 45 >>"$LOG" 2>&1 || true
    trim_bundle "$rd"
  done
done

clean_room
rm -f "$FIOF"
say "=== PHASE 7 (disk) DONE -> $OUT ==="
