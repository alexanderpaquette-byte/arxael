#!/usr/bin/env bash
# Validate the Gradle #8375 scar: concurrent worktrees sharing ONE writable
# build cache contend on the cache lock (LockTimeoutException / serialization); per-worktree caches
# (our fix) run clean and parallel. Runs gradle directly (not via the daemon) for a clean isolation.
set -uo pipefail
OUT="${1:?need out dir}"; mkdir -p "$OUT/scar8375"
GRADLE=/opt/gradle/gradle-8.10.2/bin/gradle
BASE=/tmp/scar8375; M=8
rm -rf "$BASE"; mkdir -p "$BASE"
for i in $(seq 1 $M); do cp -r /tmp/bench-test "$BASE/wt$i"; done
GUH="$BASE/guh"
# warm the shared user home once (download deps) so the timed runs aren't measuring the JUnit download
$GRADLE -p "$BASE/wt1" --gradle-user-home "$GUH" test -q >/dev/null 2>&1 || true

run_mode() {
  local mode=$1 shared="$BASE/shared-cache"
  rm -rf "$shared"; mkdir -p "$shared"
  rm -rf "$BASE"/wt*/bc "$BASE"/wt*/.gradle 2>/dev/null || true
  local pids=() t0 t1; t0=$(date +%s)
  for i in $(seq 1 $M); do
    local cache; [ "$mode" = off ] && cache="$shared" || cache="$BASE/wt$i/bc"
    cat > "$BASE/wt$i/init.gradle" <<EOF
gradle.settingsEvaluated { s -> s.buildCache { local { directory = new File("$cache") } } }
EOF
    ( $GRADLE -p "$BASE/wt$i" --gradle-user-home "$GUH" --build-cache --rerun-tasks \
        --init-script "$BASE/wt$i/init.gradle" test -q > "$OUT/scar8375/$mode-$i.log" 2>&1
      echo $? > "$BASE/wt$i/rc" ) &
    pids+=($!)
  done
  for p in "${pids[@]}"; do wait "$p"; done
  t1=$(date +%s)
  local locks nonzero
  locks=$(grep -lEi "LockTimeout|Timeout waiting for lock|could not.*lock|another process" \
          "$OUT/scar8375/$mode"-*.log 2>/dev/null | wc -l)
  nonzero=$(grep -vc 0 "$BASE"/wt*/rc 2>/dev/null | awk -F: '{s+=$2} END{print s+0}')
  echo "{\"mode\":\"$mode\",\"workers\":$M,\"wall_s\":$((t1-t0)),\"lock_errors\":$locks,\"nonzero_exits\":$nonzero}"
}

echo "=== Gradle #8375: shared writable cache (OFF) vs per-worktree (ON) ==="
run_mode off | tee -a "$OUT/scar8375/result.jsonl"
run_mode on  | tee -a "$OUT/scar8375/result.jsonl"
rm -rf "$BASE"
