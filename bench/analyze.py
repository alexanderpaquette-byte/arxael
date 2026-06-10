#!/usr/bin/env python3
"""Analyze a sweep's results.jsonl -> per-arm curves + the headline collapse point.

The headline: container-per-agent falls over at X agents; shared-warm sustains
2-3xX. Collapse = the first agent level where an arm degrades: wedges appear, swap is touched, or
goodput falls back below an earlier peak (the box past its knee). RAM is the money curve.
"""
import collections
import json
import sys


def load(path):
    rows = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def collapse_point(series):
    """First agents level where the arm degrades. series: list of dicts sorted by agents."""
    peak_goodput = 0.0
    for r in series:
        peak_goodput = max(peak_goodput, r["goodput_per_min"])
        degraded = (
            r.get("wedge_rate", 0) > 0.05 or
            r.get("peak_swap_used_mb", 0) > 256 or
            (peak_goodput > 0 and r["goodput_per_min"] < 0.7 * peak_goodput)
        )
        if degraded:
            return r["agents"]
    return None  # never collapsed within the ramp


def fmt(series, arm):
    print(f"\n  {arm} arm")
    print(f"    {'agents':>6} {'goodput/min':>12} {'p95_s':>8} {'wedge%':>7} "
          f"{'peakRAM_GB':>11} {'gradleD':>8} {'JVMrss_GB':>10} {'diskUtil%':>10} {'wrIOPS':>8}")
    for r in series:
        print(f"    {r['agents']:>6} {r['goodput_per_min']:>12.1f} {r['p95_latency_s']:>8.1f} "
              f"{100*r.get('wedge_rate',0):>6.1f}% {r.get('peak_mem_used_mb',0)/1024:>11.1f} "
              f"{r.get('peak_gradle_daemons',0):>8} {r.get('peak_jvm_rss_mb',0)/1024:>10.1f} "
              f"{r.get('peak_disk_util_pct',0):>9.1f}% {r.get('peak_write_iops',0):>8.0f}")


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "bench/out/test/results.jsonl"
    rows = load(path)
    by_cores = collections.defaultdict(lambda: collections.defaultdict(list))
    for r in rows:
        by_cores[r["cores"]][r["arm"]].append(r)

    for cores in sorted(by_cores):
        print(f"\n=== cores={cores} profile={rows[0].get('profile','?')} "
              f"box={rows[0].get('total_ram_mb',0)/1024:.0f}GB RAM ===")
        cps = {}
        for arm in ("warm", "container"):
            series = sorted(by_cores[cores].get(arm, []), key=lambda r: r["agents"])
            if not series:
                continue
            fmt(series, arm)
            cps[arm] = collapse_point(series)

        print("\n  collapse points:")
        for arm in ("warm", "container"):
            cp = cps.get(arm)
            print(f"    {arm:10}: {'sustained whole ramp' if cp is None else f'collapses at {cp} agents'}")
        if cps.get("container") and cps.get("warm"):
            ratio = cps["warm"] / cps["container"]
            print(f"    -> shared-warm sustains {ratio:.1f}x the container-per-agent collapse point")
        elif cps.get("container") and cps.get("warm") is None:
            mx = max(r["agents"] for r in by_cores[cores]["warm"])
            print(f"    -> container collapses at {cps['container']}; shared-warm sustained the full ramp "
                  f"(>= {mx} agents, >= {mx/cps['container']:.1f}x)")


if __name__ == "__main__":
    main()
