#!/usr/bin/env bash
# Real quality numbers for the substrate itself: tests + coverage (JaCoCo) + mutation (PIT) +
# security scan (Trivy). Run from repo root. Prints a one-screen summary.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || { echo "cannot cd to repo root $REPO" >&2; exit 1; }

echo "== tests + coverage =="
./gradlew :core:test :core:jacocoTestReport --console=plain -q

python3 - <<'PY'
import xml.etree.ElementTree as ET
t = ET.parse("core/build/reports/jacoco/test/jacocoTestReport.xml")
for c in t.getroot().findall("counter"):
    typ, miss, cov = c.get("type"), int(c.get("missed")), int(c.get("covered"))
    tot = miss + cov
    if tot:
        print(f"  coverage {typ:11} {cov}/{tot} = {100*cov/tot:.1f}%")
PY

echo "== mutation (PIT) =="
if ./gradlew :core:pitest --console=plain -q 2>/dev/null; then
  python3 - <<'PY'
import xml.etree.ElementTree as ET, glob, collections
f = glob.glob("core/build/reports/pitest/**/mutations.xml", recursive=True)
try:
    muts = ET.parse(f[0]).getroot().findall("mutation") if f else []
except Exception as e:                       # half-written / empty report -> report, don't crash the gate
    print(f"  (could not parse mutation report: {e})"); muts = []
if muts:
    by = collections.Counter(m.get("status") for m in muts)
    total, killed = len(muts), by.get("KILLED", 0)
    covered = total - by.get("NO_COVERAGE", 0)
    print(f"  mutants {total}  killed {killed}  survived {by.get('SURVIVED',0)}  no-cov {by.get('NO_COVERAGE',0)}")
    if covered:
        print(f"  mutation score (covered classes) = {100*killed/covered:.1f}%")
PY
else
  echo "  (pitest run failed; see core/build/reports/pitest/)"
fi

echo "== security (Trivy: vuln+secret+misconfig, HIGH/CRITICAL) =="
trivy fs --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --no-progress -q \
  --skip-dirs '**/build' --skip-dirs '**/.gradle' . 2>/dev/null | tail -20
