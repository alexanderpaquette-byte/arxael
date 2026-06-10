#!/usr/bin/env bash
# Shared helpers for the benchmark phase scripts. Source this; don't run it.
# NOTE: pkill -f GradleDaemon is SAFE inside a script file (this process's argv is "bash <file>",
# which does not contain "GradleDaemon"); it is NOT safe inline in a one-off shell command.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(dirname "$HERE")"

clean_room() {
  pkill -f GradleDaemon 2>/dev/null || true
  # rm -fv: the gradle image declares a cache VOLUME; without -v each container leaks a ~150MB
  # anonymous volume (filled the disk once). prune is the safety net on this dedicated box.
  sudo docker ps -aq --filter "name=arxbench_" | xargs -r sudo docker rm -fv >/dev/null 2>&1 || true
  sudo docker volume prune -f >/dev/null 2>&1 || true
  sudo systemctl stop arxbench.slice 2>/dev/null || true
  sync; sleep 2
}

# Trim a per-cell observability bundle to keep disk bounded over a long campaign: drop the big
# atop.raw replay for cells we won't forensically inspect, keep the small JSON timeseries + result.
trim_bundle() { rm -f "$1/atop.raw" 2>/dev/null || true; }

disk_free_gb() { df -BG --output=avail / | tail -1 | tr -dc '0-9'; }

# Abort a phase early if disk gets dangerously low (defensive after the ENOSPC incident).
guard_disk() {
  local free; free=$(disk_free_gb)
  if [ "${free:-99}" -lt 8 ]; then
    echo "[guard] LOW DISK (${free}GB) — pruning + stopping to avoid ENOSPC"; clean_room
    return 1
  fi
  return 0
}
