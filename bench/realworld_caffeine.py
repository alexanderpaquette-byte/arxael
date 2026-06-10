#!/usr/bin/env python3
"""Real-world validation against a real GRADLE OSS project (ben-manes/caffeine) via its OWN wrapper.

Complements the gson (Maven) run: this exercises the gradlew adapter on real gradle code. Many closed-loop
agents add a real JUnit5 test to caffeine's core module and submit PRs; the real MergeOrchestrator gates each
batch with `./gradlew :caffeine:test --tests com.github.benmanes.caffeine.arxael.*` (caffeine's OWN gradle
9.6 wrapper, scoped to the agents' tests so gates stay tractable after the one-time distribution download).
Good PRs land; a deliberately-broken one is caught. main must stay green.

Args: <caffeine_dir> <agents> <window_s>
"""
import json, os, pathlib, shutil, subprocess, sys, threading, time, urllib.request
import merge_sim as ms

PROJ = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/caffeine")
AGENTS = int(sys.argv[2]) if len(sys.argv) > 2 else 6
WINDOW = int(sys.argv[3]) if len(sys.argv) > 3 else 300
INTERVAL = float(os.environ.get("RW_INTERVAL", "10"))
BAD_RATE = float(os.environ.get("RW_BAD_RATE", "0.1"))
ROOT = pathlib.Path("/tmp/rwcaff")
PORT = 8813
GIT = threading.Lock(); CNT = iter(range(1, 1_000_000)); CL = threading.Lock()
TEST_DIR = "caffeine/src/test/java/com/github/benmanes/caffeine/arxael"


def post(b, p, o, t=600):
    r = urllib.request.Request(b + p, data=json.dumps(o).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(r, timeout=t) as x: return json.loads(x.read())


def get(b, p):
    with urllib.request.urlopen(b + p, timeout=30) as x: return json.loads(x.read())


def test_src(cls, good):
    rhs = "2" if good else "3"
    return (f"package com.github.benmanes.caffeine.arxael;\n"
            f"import org.junit.jupiter.api.Test;\n"
            f"import static org.junit.jupiter.api.Assertions.assertEquals;\n"
            f"final class {cls} {{ @Test void t() {{ assertEquals({rhs}, 1 + 1); }} }}\n")


def landed(bare, rel): return bool(getattr(ms.git(["ls-tree", "main", "--", rel], cwd=bare), "stdout", "").strip())


def agent(aid, bare, base, deadline, st):
    wt = ROOT / f"wt{aid}"
    with GIT: ms.git(["worktree", "add", "-q", "--detach", str(wt), "main"], cwd=bare)
    (wt / TEST_DIR).mkdir(parents=True, exist_ok=True)
    while time.monotonic() < deadline:
        with CL: n = next(CNT)
        good = (hash((aid, n)) % 1000) / 1000.0 >= BAD_RATE
        cls = f"A{aid}_{n}Test"; br = f"c{aid}_{n}"; rel = f"{TEST_DIR}/{cls}.java"
        try:
            with GIT:
                ms.git(["checkout", "-q", "-B", br, "main"], cwd=wt)
                (wt / TEST_DIR / f"{cls}.java").write_text(test_src(cls, good))
                ms.git(["add", "-A"], cwd=wt); ms.git(["commit", "-q", "-m", br], cwd=wt)
            post(base, "/merge/submit", {"branch": br, "agentId": f"a{aid}"})
            st["good" if good else "bad"] += 1
        except Exception:
            st["err"] += 1; time.sleep(INTERVAL); continue
        cap = time.monotonic() + 600
        while time.monotonic() < cap and not landed(bare, rel): time.sleep(4)
        time.sleep(INTERVAL)


def main():
    if ROOT.exists(): shutil.rmtree(ROOT)
    ROOT.mkdir(parents=True)
    bare = ROOT / "caffeine.git"
    subprocess.run(["git", "clone", "--bare", "-q", str(PROJ), str(bare)], check=True)
    for k, v in [("user.email", "rw@arxael"), ("user.name", "rw")]: ms.git(["config", k, v], cwd=bare)
    # caffeine's default branch is `master`; the orchestrator works on `main` -> make `main` exist
    head = (getattr(ms.git(["symbolic-ref", "--short", "HEAD"], cwd=bare), "stdout", "") or "master").strip()
    if head != "main":
        ms.git(["branch", "main", head], cwd=bare)
        print(f"created 'main' from default branch '{head}'")

    os.environ["ARXAEL_MERGE_GATE_ADAPTER"] = "gradlew"
    os.environ["ARXAEL_GRADLEW_CMD"] = "./gradlew :caffeine:test --tests com.github.benmanes.caffeine.arxael.* --console=plain"
    proc, base = ms.start_daemon(PORT, ROOT / "state", cores=int(os.environ.get("RW_CORES", "8")),
                                 ro_cache=None, max_concurrent=int(os.environ.get("RW_CORES", "8")),
                                 build_cache=False, reserved_high=2)
    st = {"good": 0, "bad": 0, "err": 0}
    try:
        print("registered:", json.dumps(post(base, "/merge/register", {"repo": str(bare), "threshold": 4, "gateWorktrees": 2})))
        t0 = time.monotonic(); deadline = t0 + WINDOW
        ts = [threading.Thread(target=agent, args=(i, bare, base, deadline, st)) for i in range(AGENTS)]
        for t in ts: t.start()
        for t in ts: t.join()
        print(f"agents done: {json.dumps(st)}; draining…")
        last = {}
        for _ in range(400):
            last = get(base, "/merge/status")
            if last.get("queueDepth", 0) == 0 and last.get("inFlightGates", 0) == 0 and last.get("processing", 0) == 0:
                time.sleep(2); last = get(base, "/merge/status")
                if last.get("queueDepth", 0) == 0 and last.get("processing", 0) == 0: break
            time.sleep(3)
        el = time.monotonic() - t0; ln = last.get("landed", 0)
        print("\n=== RESULT (real gradle caffeine via its own wrapper) ===")
        print(json.dumps({**last, "agents": AGENTS, "elapsed_s": round(el, 1),
                          "merges_per_min": round(ln / el * 60, 1) if el else 0, **st}, indent=2))
        gwt = ROOT / "gate-main"
        with GIT: ms.git(["worktree", "add", "-q", "--detach", str(gwt), "main"], cwd=bare)
        g = post(base, "/invoke", {"adapter": "gradlew", "worktree": str(gwt), "agentId": "verify"})
        ok = ln > 0 and last.get("errors", 0) == 0 and g.get("status") == "SUCCESS"
        print(f"\n  main HEAD after the run: {g.get('status')}")
        print("  " + ("\033[32mPASS\033[0m real GRADLE OSS (caffeine) via its own wrapper: agents landed real "
                      "PRs, main GREEN" if ok else "\033[31mCHECK\033[0m see above"))
        sys.exit(0 if ok else 1)
    finally:
        try: urllib.request.urlopen(urllib.request.Request(base + "/shutdown", data=b"{}"), timeout=5)
        except Exception: pass
        try: proc.wait(timeout=10)
        except Exception: proc.kill()


if __name__ == "__main__":
    main()
