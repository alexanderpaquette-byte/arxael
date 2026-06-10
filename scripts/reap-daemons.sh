#!/usr/bin/env bash
# Safely reap arxael + Gradle JVMs left by tests/benchmarks/smokes.
#
# WHY THIS EXISTS: `pkill -f <pattern>` matches the FULL command line of every process — INCLUDING the
# shell running the pkill itself if the pattern appears in the command. That self-match kills the caller
# (exit 144). This script never does pattern matching that can match itself: it resolves PIDs via `jps`
# (JVM process list — only Java processes, identified by main class) and kills those PIDs directly. The
# one non-JVM case (a python load driver) is matched with a bracket-split pattern that cannot match this
# script's own argv, and we additionally exclude our own PID.
#
# Usage: scripts/reap-daemons.sh [-9]   (-9 forces SIGKILL; default SIGTERM then SIGKILL stragglers)
set -u
SIG="${1:-}"

self=$$
killed=0

# 1) JVMs: arxael daemon (dev.arxael.MainKt) + Gradle daemons (org.gradle...GradleDaemon) + Gradle wrapper.
if command -v jps >/dev/null 2>&1; then
  pids=$(jps -l 2>/dev/null | awk '/dev\.arxael|GradleDaemon|org\.gradle\.launcher/{print $1}')
  for p in $pids; do
    [ "$p" = "$self" ] && continue
    kill ${SIG} "$p" 2>/dev/null && killed=$((killed+1))
  done
fi

# 2) Non-JVM: the python merge load driver. Bracket-split the literal so this script's own argv can't match.
for p in $(ps -eo pid,args | awk '/merge_http_loa[d]\.py|merge_si[m]\.py/{print $1}'); do
  [ "$p" = "$self" ] && continue
  kill ${SIG:--TERM} "$p" 2>/dev/null && killed=$((killed+1))
done

remaining=$(jps -l 2>/dev/null | grep -cE 'dev\.arxael|GradleDaemon' || true)
echo "reaped=${killed} remaining_jvm=${remaining}"
# Always succeed: a failed kill on an already-gone PID must never abort a caller's command chain.
exit 0
