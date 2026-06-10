#!/usr/bin/env bash
# Phase-0 wedge diagnostics: one full forensic snapshot of the warm executor + its gradle daemons.
# Usage: diag_capture.sh <port> <out_dir> <gradle_user_home_substr> [tag]
# Captures (no dark spots): /health, arxael daemon jstack + fd/socket counts, EVERY gradle daemon
# jstack (filtered to THIS run's user home so the IDE's daemons are excluded), event-log in/out
# deltas (stuck-in-flight proof), and system vitals (load/mem/iostat). Safe to call repeatedly.
set -uo pipefail
PORT="${1:?port}"; OUT="${2:?out_dir}"; GUH="${3:?gradle-user-home substring}"; TAG="${4:-$(date +%H%M%S)}"
D="$OUT/cap-$TAG"; mkdir -p "$D"

# --- /health snapshot (executor: inFlight, permitsAvailable, warmServers) ---
curl -s --max-time 3 "http://127.0.0.1:$PORT/health" > "$D/health.json" 2>/dev/null || echo '{"err":"health unreachable"}' > "$D/health.json"

# --- identify the arxael daemon: java proc whose argv carries our main class ---
ARX_PID="$(pgrep -f 'dev.arxael.MainKt' | head -1)"
[ -z "$ARX_PID" ] && ARX_PID="$(pgrep -f 'install/core/lib' | head -1)"
echo "arxael_pid=$ARX_PID" > "$D/pids.txt"

if [ -n "$ARX_PID" ]; then
  jstack "$ARX_PID" > "$D/arxael.jstack" 2>&1 || echo "jstack failed" > "$D/arxael.jstack"
  echo "fd_count=$(ls /proc/$ARX_PID/fd 2>/dev/null | wc -l)" >> "$D/pids.txt"
  echo "sockets=$(ls -l /proc/$ARX_PID/fd 2>/dev/null | grep -c socket)" >> "$D/pids.txt"
  echo "threads=$(ls /proc/$ARX_PID/task 2>/dev/null | wc -l)" >> "$D/pids.txt"
fi

# --- my gradle daemons only (cmdline references this run's gradle user home) ---
MY_DAEMONS=()
for p in $(pgrep -f 'org.gradle.launcher.daemon.bootstrap.GradleDaemon'); do
  if tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -q "$GUH"; then MY_DAEMONS+=("$p"); fi
done
echo "my_gradle_daemons=${MY_DAEMONS[*]:-none} (count=${#MY_DAEMONS[@]})" >> "$D/pids.txt"
for p in "${MY_DAEMONS[@]:-}"; do
  [ -z "$p" ] && continue
  jstack "$p" > "$D/gradled-$p.jstack" 2>&1 || echo "jstack $p failed" > "$D/gradled-$p.jstack"
done

# --- event-log deltas: invoke_start - invoke_done = currently stuck in server.run() ---
EV="$(dirname "$(echo "$GUH")")/events.jsonl"
# fall back: search under /tmp/arxbench for events.jsonl
[ -f "$EV" ] || EV="$(find /tmp/arxbench -name events.jsonl 2>/dev/null | head -1)"
if [ -f "$EV" ]; then
  { echo "events.jsonl=$EV"
    for t in invoke_received invoke_start invoke_done invoke_overloaded invoke_error server_open watchdog_unhealthy; do
      echo "  $t=$(grep -c "\"$t\"" "$EV" 2>/dev/null)"
    done
    echo "  STUCK_in_run=$(( $(grep -c '"invoke_start"' "$EV") - $(grep -c '"invoke_done"' "$EV") ))"
  } > "$D/events_delta.txt"
fi

# --- system vitals ---
{ echo "=== loadavg ==="; cat /proc/loadavg
  echo "=== free -m ==="; free -m | sed -n '1,3p'
  echo "=== top cpu (java) ==="; top -b -n1 | grep -E 'java|GradleDaemon|%Cpu|load' | head -20
  echo "=== iostat ==="; iostat -dx nvme0n1 1 2 2>/dev/null | tail -8
} > "$D/vitals.txt" 2>&1

echo "captured -> $D"
