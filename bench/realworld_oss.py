#!/usr/bin/env python3
"""Real-world validation against an ACTUAL OSS project (google/gson) — not a synthetic fixture.

Runs inside the container (see realworld_oss.sh). gson is a real, widely-used library with a real Maven test
suite (~35s). Many agents each add a real, compiling test to gson's core module, branch-gate, and submit a
PR; the real shipped MergeOrchestrator gates each batch with gson's OWN `mvn` test suite and lands the green
ones on main. A few agents submit a deliberately-broken test to prove the gate catches it. At the end we gate
main HEAD itself — it must be green (no broken change landed).

Args: <gson_dir> <agents> <window_s>
"""
import json, os, pathlib, shutil, subprocess, sys, threading, time, urllib.request
import merge_sim as ms

GSON = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/gson")
AGENTS = int(sys.argv[2]) if len(sys.argv) > 2 else 16
WINDOW = int(sys.argv[3]) if len(sys.argv) > 3 else 300
# realistic agent cadence: a real agent does minutes of work between PRs, so it doesn't fire continuously.
INTERVAL = float(os.environ.get("RW_INTERVAL", "15"))   # seconds between an agent's submissions
BAD_RATE = float(os.environ.get("RW_BAD_RATE", "0.08"))  # fraction submitted broken (exercises the gate + bisection)
ROOT = pathlib.Path("/tmp/rwoss")
PORT = 8811
GIT_LOCK = threading.Lock()
COUNTER = iter(range(1, 10_000_000))
COUNTER_LOCK = threading.Lock()
TEST_DIR = "gson/src/test/java/com/google/gson/arxael"  # gson core module's test tree


def post(base, path, obj, t=300):
    req = urllib.request.Request(base + path, data=json.dumps(obj).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=t) as r:
        return json.loads(r.read())


def get(base, path):
    with urllib.request.urlopen(base + path, timeout=30) as r:
        return json.loads(r.read())


def junit5() -> bool:
    """Detect gson's test framework so generated tests compile."""
    for p in (GSON / "gson/src/test/java").rglob("*.java"):
        t = p.read_text(errors="ignore")
        if "org.junit.jupiter" in t:
            return True
        if "import org.junit.Test" in t:
            return False
    return True


def test_src(cls: str, j5: bool, good: bool) -> str:
    rhs = "2" if good else "3"  # good: 1+1==2 ; bad: 1+1==3 (fails the gate)
    if j5:
        return (f"package com.google.gson.arxael;\n"
                f"import org.junit.jupiter.api.Test;\n"
                f"import static org.junit.jupiter.api.Assertions.assertEquals;\n"
                f"class {cls} {{ @Test void t() {{ assertEquals({rhs}, 1 + 1); }} }}\n")
    return (f"package com.google.gson.arxael;\n"
            f"import org.junit.Test;\n"
            f"import static org.junit.Assert.assertEquals;\n"
            f"public class {cls} {{ @Test public void t() {{ assertEquals({rhs}, 1 + 1); }} }}\n")


def landed_on_main(bare, relpath) -> bool:
    r = ms.git(["ls-tree", "main", "--", relpath], cwd=bare)
    return bool(getattr(r, "stdout", "").strip())


def agent_loop(aid, bare, base, j5, deadline, stats):
    """CLOSED-LOOP agent (realistic): submit one PR, then WAIT for it to merge before the next — so a real
    fleet self-limits to the substrate's capacity instead of flooding an unbounded queue."""
    wt = ROOT / f"wt{aid}"
    with GIT_LOCK:
        ms.git(["worktree", "add", "-q", "--detach", str(wt), "main"], cwd=bare)
    (wt / TEST_DIR).mkdir(parents=True, exist_ok=True)
    while time.monotonic() < deadline:
        with COUNTER_LOCK:
            n = next(COUNTER)
        good = (hash((aid, n)) % 1000) / 1000.0 >= BAD_RATE  # a small fraction submitted broken
        cls = f"A{aid}_{n}Test"
        br = f"rw{aid}_{n}"
        relpath = f"{TEST_DIR}/{cls}.java"
        try:
            with GIT_LOCK:
                ms.git(["checkout", "-q", "-B", br, "main"], cwd=wt)
                (wt / TEST_DIR / f"{cls}.java").write_text(test_src(cls, j5, good))
                ms.git(["add", "-A"], cwd=wt)
                ms.git(["commit", "-q", "-m", br], cwd=wt)
            post(base, "/merge/submit", {"branch": br, "agentId": f"agent{aid}"})
            stats["submitted_good" if good else "submitted_bad"] += 1
        except Exception:
            stats["errors_local"] += 1
            time.sleep(INTERVAL); continue
        # closed loop: wait for this PR to resolve (good -> lands on main; bad -> never lands, cap the wait)
        cap = time.monotonic() + 300
        while time.monotonic() < cap and not landed_on_main(bare, relpath):
            time.sleep(3)
        time.sleep(INTERVAL)  # the agent does other work before its next PR


def main():
    if ROOT.exists():
        shutil.rmtree(ROOT)
    ROOT.mkdir(parents=True)
    j5 = junit5()
    print(f"gson test framework: {'JUnit5' if j5 else 'JUnit4'}")

    # bare repo from the REAL gson checkout
    bare = ROOT / "gson.git"
    subprocess.run(["git", "clone", "--bare", "-q", str(GSON), str(bare)], check=True)
    for k, v in [("user.email", "rw@arxael"), ("user.name", "rw")]:
        ms.git(["config", k, v], cwd=bare)

    # gate gson's OWN real test suite, scoped to the core module (real build, ~35s)
    os.environ["ARXAEL_MERGE_GATE_ADAPTER"] = "maven"
    os.environ["ARXAEL_MAVEN_CMD"] = "mvn -q -pl gson -am test"
    proc, base = ms.start_daemon(PORT, ROOT / "state", cores=int(os.environ.get("RW_CORES", "8")),
                                 ro_cache=None, max_concurrent=int(os.environ.get("RW_CORES", "8")),
                                 build_cache=False, reserved_high=2)
    stats = {"submitted_good": 0, "submitted_bad": 0, "errors_local": 0}
    try:
        reg = post(base, "/merge/register", {"repo": str(bare), "threshold": 4, "gateWorktrees": 3})
        print("registered:", json.dumps(reg))

        t0 = time.monotonic()
        deadline = t0 + WINDOW
        threads = [threading.Thread(target=agent_loop, args=(i, bare, base, j5, deadline, stats))
                   for i in range(AGENTS)]
        for t in threads: t.start()
        for t in threads: t.join()
        print(f"agents done: {json.dumps(stats)}; draining the backlog…")

        # TRUE idle = empty queue AND no in-flight gate AND nothing mid-merge. (inFlightGates alone is 0 for
        # the batched path, which would falsely declare 'done' with a full queue — the bug this exposed.)
        last = {}
        def idle(s):
            return s.get("queueDepth", 0) == 0 and s.get("inFlightGates", 0) == 0 and s.get("processing", 0) == 0
        for _ in range(600):
            last = get(base, "/merge/status")
            if idle(last):
                time.sleep(2); last = get(base, "/merge/status")
                if idle(last):
                    break
            time.sleep(2)
        elapsed = time.monotonic() - t0
        landed = last.get("landed", 0)
        mpm = round(landed / elapsed * 60, 1) if elapsed else 0
        print("\n=== RESULT (real gson project, real mvn test gate) ===")
        print(json.dumps({**last, "agents": AGENTS, "elapsed_s": round(elapsed, 1),
                          "merges_per_min": mpm, **stats}, indent=2))

        # soundness: gate main HEAD with gson's real suite -> must be GREEN
        gate_wt = ROOT / "gate-main"
        with GIT_LOCK:
            ms.git(["worktree", "add", "-q", "--detach", str(gate_wt), "main"], cwd=bare)
        g = post(base, "/invoke", {"adapter": "maven", "worktree": str(gate_wt), "agentId": "rw-verify"})
        main_green = g.get("status") == "SUCCESS"

        ok = landed > 0 and last.get("errors", 0) == 0 and main_green
        print(f"\n  main HEAD after the run: {g.get('status')}")
        print("  " + ("\033[32mPASS\033[0m real OSS project: agents landed real PRs through gson's own mvn "
                      "test suite, main GREEN, no errors" if ok else "\033[31mCHECK\033[0m see above"))
        sys.exit(0 if ok else 1)
    finally:
        try:
            urllib.request.urlopen(urllib.request.Request(base + "/shutdown", data=b"{}"), timeout=5)
        except Exception:
            pass
        try: proc.wait(timeout=10)
        except Exception: proc.kill()


if __name__ == "__main__":
    main()
