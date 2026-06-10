#!/usr/bin/env python3
"""Load-test the SHIPPED Kotlin merge orchestrator via the daemon's /merge HTTP endpoints.

Unlike merge_sim.py (which runs its OWN in-process Python MergeQueue to explore strategies), this drives
the REAL orchestrator that ships in core/ (dev.arxael.merge): register the project, fan many agents at
POST /merge/submit, let the daemon's MergeOrchestrator route/land/revert on the warm substrate, and poll
GET /merge/status. It validates the production path end-to-end under concurrency with the real gradle gate
(reserved high lane, per-worktree homes, RO-dep-cache) — the thing the unit tests can't stress.

Reuses merge_sim's fixture/git/edit/daemon helpers so the workload is identical to the prototype's.
"""
import argparse
import json
import pathlib
import random
import re
import threading
import time
import urllib.request

import merge_sim as ms

GIT_LOCK = threading.Lock()


def post(base, path, obj):
    req = urllib.request.Request(base + path, data=json.dumps(obj).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def get(base, path):
    with urllib.request.urlopen(base + path, timeout=30) as r:
        return json.loads(r.read())


def forward_deps(fixture, n):
    """':modj' -> [':modK', ...] parsed from each module's build.gradle.kts project(':modK') lines."""
    fd = {}
    for j in range(n):
        bg = pathlib.Path(fixture) / f"mod{j}" / "build.gradle.kts"
        deps = []
        if bg.exists():
            deps = [f":mod{m.group(1)}" for m in re.finditer(r'project\(":mod(\d+)"\)', bg.read_text())]
        fd[f":mod{j}"] = deps
    return fd


def agent_loop(aid, bare, base, args, deadline):
    rnd = random.Random(aid * 7919)
    root = pathlib.Path(bare).parent
    wt = root / f"wt-agent{aid}"
    if not wt.exists():  # may already exist from a pre-warm phase
        with GIT_LOCK:
            ms.git(["worktree", "add", "-q", "--detach", str(wt), "main"], cwd=bare)
    while time.monotonic() < deadline:
        br = f"a{aid}-{int(time.monotonic() * 1000) % 1000000}"
        with GIT_LOCK:
            r = ms.git(["checkout", "-q", "-B", br, "main"], cwd=wt)
        if r.returncode != 0:
            time.sleep(0.3); continue
        bad = args.bad_rate > 0 and rnd.random() < args.bad_rate
        tag = (aid * 2654435761 + rnd.randint(0, 1 << 20)) & 0xFFFFFF
        midx = tag % max(1, args.modules)
        meth = (tag // 97) % max(1, args.methods)
        try:
            if ms.edit_slice(wt, midx, meth, tag, bad):
                ms.git(["add", "-A"], cwd=wt)
                ms.git(["commit", "-q", "-m", br], cwd=wt)
                # branch-gate is the agent's job (phase-5: the soundness lever) — skip the bad ones here only
                # to model gating; with --no-branch-gate, bad PRs flow through so the async gate must catch them.
                if args.branch_gate and bad:
                    pass  # a real agent's branch test would catch it; don't submit
                else:
                    post(base, "/merge/submit", {"branch": br, "module": f":mod{midx}", "agentId": f"agent{aid}"})
        except Exception:
            pass
        time.sleep(random.expovariate(args.rate))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixture", default="/tmp/wide-dag")
    ap.add_argument("--modules", type=int, default=12)
    ap.add_argument("--methods", type=int, default=3)
    ap.add_argument("--agents", type=int, default=6)
    ap.add_argument("--rate", type=float, default=0.25)
    ap.add_argument("--bad-rate", type=float, default=0.12, dest="bad_rate")
    ap.add_argument("--branch-gate", action="store_true", default=True, dest="branch_gate")
    ap.add_argument("--no-branch-gate", action="store_false", dest="branch_gate")
    ap.add_argument("--threshold", type=int, default=4)
    ap.add_argument("--window", type=float, default=75)
    ap.add_argument("--cores", type=int, default=12)
    ap.add_argument("--max-concurrent", type=int, default=12, dest="max_concurrent")
    ap.add_argument("--reserved-high", type=int, default=2, dest="reserved_high")
    ap.add_argument("--ro-dep-cache", default="/tmp/ro-seed-home/caches")
    ap.add_argument("--auto-warm", action="store_true",
                    help="don't pre-seed GRADLE_RO_DEP_CACHE; force the daemon to auto-warm it on register "
                         "(validates the shipped shared-but-unlocked dep cache end-to-end)")
    ap.add_argument("--port", type=int, default=8796)
    ap.add_argument("--root", default="/tmp/arxmerge-http")
    ap.add_argument("--warmup", action="store_true",
                    help="pre-warm each agent's worktree (POST /warmup) BEFORE the timed window, so the "
                         "per-worktree cold-start tax doesn't count against throughput")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    bare = ms.setup(args.fixture, args.root)
    # --auto-warm: pass no RO cache so the daemon must warm one itself on register (the shipped feature).
    ro_cache = None if args.auto_warm else args.ro_dep_cache
    proc, base = ms.start_daemon(args.port, pathlib.Path(args.root) / "state", args.cores,
                                 ro_cache, args.max_concurrent, build_cache=True,
                                 reserved_high=args.reserved_high)
    try:
        reg = post(base, "/merge/register", {"repo": str(bare),
                                             "forwardDeps": forward_deps(args.fixture, args.modules),
                                             "threshold": args.threshold, "gateWorktrees": 4})
        print("registered:", json.dumps(reg))

        # Optional: pre-warm each agent's worktree BEFORE the timed window (the cold-start tax is paid here,
        # in parallel, not against throughput). Creates the worktree then POSTs /warmup to spawn its daemon.
        if args.warmup:
            def warm(aid):
                wt = pathlib.Path(bare).parent / f"wt-agent{aid}"
                with GIT_LOCK:
                    ms.git(["worktree", "add", "-q", "--detach", str(wt), "main"], cwd=bare)
                post(base, "/warmup", {"adapter": "gradle", "worktree": str(wt)})
            wt_threads = [threading.Thread(target=warm, args=(i,)) for i in range(args.agents)]
            for t in wt_threads: t.start()
            for t in wt_threads: t.join()
            print("pre-warmed", args.agents, "agent worktrees")

        t0 = time.monotonic()
        deadline = t0 + args.window
        threads = [threading.Thread(target=agent_loop, args=(i, bare, base, args, deadline))
                   for i in range(args.agents)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        # let the orchestrator drain the queue + settle async gates
        time.sleep(2)
        last = None
        def idle(s):
            # TRUE idle: empty queue AND no in-flight async gate AND nothing mid-merge. (inFlightGates alone
            # is 0 for the batched path, so it would falsely declare 'done' with a deep queue still draining.)
            return (s.get("queueDepth", 0) == 0 and s.get("inFlightGates", 0) == 0
                    and s.get("processing", 0) == 0)
        for _ in range(600):
            s = get(base, "/merge/status")
            if idle(s):
                time.sleep(1)
                last = get(base, "/merge/status")
                if idle(last):
                    break
            last = s
            time.sleep(1)
        elapsed = time.monotonic() - t0
        s = last or get(base, "/merge/status")
        landed = s.get("landed", 0)
        res = {"agents": args.agents, "elapsed_s": round(elapsed, 1), "window_s": args.window,
               "branch_gate": args.branch_gate, **s,
               "merges_per_min": round(landed / elapsed * 60, 1) if elapsed else 0}
        print(json.dumps(res, indent=2))
        if args.out:
            with open(args.out, "a") as f:
                f.write(json.dumps(res) + "\n")
    finally:
        try:
            urllib.request.urlopen(urllib.request.Request(base + "/shutdown", data=b"{}"), timeout=5)
        except Exception:
            pass
        try:
            proc.wait(timeout=10)
        except Exception:
            proc.kill()


if __name__ == "__main__":
    main()
