#!/usr/bin/env bash
# install-service.sh — run arxael as a SUPERVISED systemd service: auto-restart on crash AND on wedge.
#
# This is the right-sized HA for the "one box you own" thesis (LIMITATIONS #2). It does NOT add a second
# machine; it makes the single box self-healing:
#   - crash  -> systemd restarts the daemon; its PrJournal re-enqueues + re-gates unfinished PRs on startup
#               (so a crash never leaves an unverified change on main — validated by crash/fault-injection testing).
#   - wedge  -> a liveness timer curls /health every 30s and restarts the daemon if it stops answering
#               (covers a hung process that systemd's process-alive check would miss).
#
# Usage:  scripts/install-service.sh [--user <unit-user>]    (needs sudo; --uninstall to remove)
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
UNIT_USER="${SUDO_USER:-$(id -un)}"
UNINSTALL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --user) UNIT_USER="${2:?--user needs a value}"; shift 2 ;;
    --uninstall) UNINSTALL=1; shift ;;
    *) echo "unknown arg: $1 (usage: $(basename "$0") [--user <unit-user>] [--uninstall])"; exit 1 ;;
  esac
done
GRADLE_HOME="$(for g in /opt/gradle/gradle-* ; do [ -x "$g/bin/gradle" ] && echo "$g" && break; done)"
LAUNCHER="$REPO/core/build/install/core/bin/core"
PORT="${ARXAEL_PORT:-8723}"
SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"

if [ "$UNINSTALL" = "1" ]; then
  $SUDO systemctl disable --now arxael.service arxael-health.timer 2>/dev/null || true
  $SUDO rm -f /etc/systemd/system/arxael.service /etc/systemd/system/arxael-health.service /etc/systemd/system/arxael-health.timer
  $SUDO systemctl daemon-reload
  echo "arxael service removed."; exit 0
fi

[ -x "$LAUNCHER" ] || { echo "build first: scripts/install.sh (no launcher at $LAUNCHER)"; exit 1; }
[ -n "$GRADLE_HOME" ] || { echo "no /opt/gradle/gradle-* found; run scripts/install.sh first"; exit 1; }

echo "installing arxael.service (user=$UNIT_USER, port=$PORT, gradle=$GRADLE_HOME)…"
$SUDO tee /etc/systemd/system/arxael.service >/dev/null <<UNIT
[Unit]
Description=arxael — warm bounded build/test executor + merge orchestrator
After=network.target
# Crash-loop limiter. MUST be in [Unit] — systemd ignores StartLimit* in [Service] (verified via
# systemd-analyze verify), which would leave a deterministic startup failure (e.g. port already in use)
# restarting every RestartSec forever. Here, >5 starts in 60s puts the unit in 'failed' instead of thrashing.
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
User=$UNIT_USER
Environment=ARXAEL_GRADLE_HOME=$GRADLE_HOME
Environment=ARXAEL_PORT=$PORT
EnvironmentFile=-/etc/arxael/arxael.env
ExecStart=$LAUNCHER
# crash recovery: on restart the PrJournal re-enqueues + re-gates unfinished PRs (no unverified change on main)
Restart=always
RestartSec=2
# Bound a graceful stop: /shutdown closes worktree-servers, which can block on an in-flight build. Give it up
# to 60s to drain (a bit over the orchestrator's 30s gate-drain) before SIGKILL, instead of systemd's 90s
# default. Either way ExecStopPost still runs afterward, so daemons are reaped even on a hard kill.
TimeoutStopSec=60
# on stop, reap ALL arxael + gradle JVMs on this host (NOT scoped to this state dir — reap-daemons.sh matches by
# JVM main class). Safe under the single-box/single-service model; on a shared host it would also reap other arxael
# or unrelated gradle daemons. Self-kill-safe (jps/PID-based, never pattern-matches the reaper itself).
ExecStopPost=$REPO/scripts/reap-daemons.sh

[Install]
WantedBy=multi-user.target
UNIT

echo "installing arxael-health.{service,timer} (wedge detection -> restart)…"
$SUDO tee /etc/systemd/system/arxael-health.service >/dev/null <<HSVC
[Unit]
Description=arxael liveness check (restarts a wedged daemon)
[Service]
Type=oneshot
Environment=ARXAEL_PORT=$PORT
EnvironmentFile=-/etc/arxael/arxael.env
# Read the port from the SAME env source as the daemon (file overrides the default), so changing ARXAEL_PORT
# can't leave the probe checking a stale port and restarting a healthy daemon every 30s.
# if /health doesn't answer within 5s, the process is wedged (not just busy) -> restart it
ExecStart=/bin/sh -c 'curl -fsS -m 5 http://127.0.0.1:\${ARXAEL_PORT}/health >/dev/null || systemctl restart arxael.service'
HSVC

$SUDO tee /etc/systemd/system/arxael-health.timer >/dev/null <<HTMR
[Unit]
Description=run the arxael liveness check every 30s
[Timer]
OnBootSec=60
OnUnitActiveSec=30
[Install]
WantedBy=timers.target
HTMR

$SUDO systemctl daemon-reload
$SUDO systemctl enable --now arxael.service arxael-health.timer
echo "✓ arxael is now a supervised service: auto-restart on crash + wedge, journal-recovers on restart."
echo "  systemctl status arxael   ·   journalctl -u arxael -f   ·   scripts/install-service.sh --uninstall"
