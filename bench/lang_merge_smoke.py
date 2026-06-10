#!/usr/bin/env python3
"""End-to-end proof that the FULL merge workflow (branch -> test -> PR -> merge to main) works for a NON-JVM
language. Builds a real pytest project, registers it with ARXAEL_MERGE_GATE_ADAPTER=pytest, then:

  - submits a GOOD branch (adds a passing test)  -> the daemon's MergeOrchestrator gates it with `pytest -q`
    on the warm executor and LANDS it on main.
  - submits a BAD branch  (adds a failing test)   -> the gate goes red and the PR is NOT landed (bounced),
    main is untouched.

This exercises the real shipped orchestrator + GitOps + the now-multi-language ExecutorMergeGate, proving the
core promise isn't gradle-only. Run: python3 bench/lang_merge_smoke.py
"""
import json, os, pathlib, shutil, sys, time, urllib.request
import merge_sim as ms

ROOT = pathlib.Path("/tmp/lang-merge")
PORT = 8807


def post(base, path, obj):
    req = urllib.request.Request(base + path, data=json.dumps(obj).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def get(base, path):
    with urllib.request.urlopen(base + path, timeout=30) as r:
        return json.loads(r.read())


def build_fixture():
    fx = pathlib.Path("/tmp/lang-merge-fixture")  # OUTSIDE ROOT (ms.setup wipes ROOT)
    if fx.exists():
        shutil.rmtree(fx)
    fx.mkdir(parents=True)
    # a minimal but real pytest project with one passing test, so `pytest -q` collects >=1 and exits 0
    (fx / "test_core.py").write_text("def test_base():\n    assert 2 + 2 == 4\n")
    return fx


def add_branch(bare, wt, name, filename, body):
    ms.git(["checkout", "-q", "-B", name, "main"], cwd=wt)
    (pathlib.Path(wt) / filename).write_text(body)
    ms.git(["add", "-A"], cwd=wt)
    ms.git(["commit", "-q", "-m", name], cwd=wt)


def poll_landed(base, want, timeout=120):
    end = time.monotonic() + timeout
    last = {}
    while time.monotonic() < end:
        last = get(base, "/merge/status")
        if last.get("inFlightGates", 0) == 0 and last.get("landed", 0) >= want:
            return last
        time.sleep(1)
    return last


def main():
    if ROOT.exists():
        shutil.rmtree(ROOT)
    fx = build_fixture()
    bare = ms.setup(str(fx), str(ROOT))

    os.environ["ARXAEL_MERGE_GATE_ADAPTER"] = "pytest"  # the whole point: gate with a non-JVM toolchain
    proc, base = ms.start_daemon(PORT, ROOT / "state", cores=4, ro_cache=None,
                                 max_concurrent=4, build_cache=False, reserved_high=1)
    ok = True
    try:
        reg = post(base, "/merge/register", {"repo": str(bare), "threshold": 4, "gateWorktrees": 2})
        print("registered:", json.dumps(reg))

        wt = ROOT / "wt"
        ms.git(["worktree", "add", "-q", "--detach", str(wt), "main"], cwd=bare)

        # 1) GOOD branch: adds a passing test -> gate green -> lands
        add_branch(bare, wt, "good", "test_feature.py", "def test_feature():\n    assert 'arxael'.islower()\n")
        post(base, "/merge/submit", {"branch": "good", "agentId": "lang-merge"})
        s = poll_landed(base, want=1)
        if s.get("landed", 0) >= 1 and s.get("errors", 0) == 0:
            print(f"  PASS  good pytest PR LANDED (landed={s.get('landed')}, reverts={s.get('reverts')})")
        else:
            ok = False; print(f"  FAIL  good PR did not land: {json.dumps(s)}")

        landed_before_bad = s.get("landed", 0)

        # 2) BAD branch: adds a failing test -> gate red -> NOT landed, main untouched
        add_branch(bare, wt, "bad", "test_broken.py", "def test_broken():\n    assert 1 == 2\n")
        post(base, "/merge/submit", {"branch": "bad", "agentId": "lang-merge"})
        time.sleep(2)
        s = poll_landed(base, want=landed_before_bad + 1, timeout=30)  # should TIME OUT (it must not land)
        if s.get("landed", 0) == landed_before_bad:
            print(f"  PASS  bad pytest PR was NOT landed (landed still {landed_before_bad}, "
                  f"bouncedSemantic={s.get('bouncedSemantic')}, reverts={s.get('reverts')})")
        else:
            ok = False; print(f"  FAIL  bad PR landed a broken change: {json.dumps(s)}")

        print("\nfinal /merge/status:", json.dumps(get(base, "/merge/status")))
    finally:
        try:
            urllib.request.urlopen(urllib.request.Request(base + "/shutdown", data=b"{}"), timeout=5)
        except Exception:
            pass
        try:
            proc.wait(timeout=10)
        except Exception:
            proc.kill()

    print("\n[lang-merge] " + ("\033[32mALL GREEN\033[0m — merge workflow proven on pytest (non-JVM)"
                               if ok else "\033[31mFAILED\033[0m"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
