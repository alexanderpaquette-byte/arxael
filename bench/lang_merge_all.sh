#!/usr/bin/env bash
# lang_merge_all.sh — run the per-language merge proof for each language, EACH FULLY ISOLATED: a separate
# process + its own daemon on its own port, NO orphan-reaper, clean teardown. This is how the product is used
# (one warm daemon per project) — not a single process churning daemons. Coexists with a running service.
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
: "${ARXAEL_GRADLE_HOME:=$(for g in /opt/gradle/gradle-*; do [ -x "$g/bin/gradle" ] && echo "$g" && break; done)}"
export ARXAEL_GRADLE_HOME
port=8850; pass=0; fail=0; skip=0
for L in pytest cargo go npm; do
  python3 "$HERE/bench/lang_merge_test.py" "$L" --port "$port"
  rc=$?
  if [ "$rc" -eq 0 ]; then pass=$((pass+1)); else fail=$((fail+1)); fi
  port=$((port+1))
done
echo "=== languages: $pass ok (incl. skips), $fail failed ==="
[ "$fail" -eq 0 ] && echo "[lang-all] ALL GREEN" || echo "[lang-all] FAILED"
[ "$fail" -eq 0 ]
