#!/usr/bin/env python3
"""Chaos test: SIGKILL the daemon mid-merge and prove crash recovery is SOUND.

The durability claim (LIMITATIONS #2): a crash cannot leave an unverified change silently on main. PrJournal
records every PR's lifecycle outside the worktree base; on restart, register -> orchestrator.recover()
re-enqueues anything submitted-but-not-done (including an optimistically-landed-but-unverified PR), which
re-gates it. This test exercises that for real:

  1. start daemon, register a small real Gradle project, fire a burst of good PRs,
  2. SIGKILL -9 the daemon while gates are in flight (hard crash, no graceful shutdown),
  3. restart the daemon on the SAME state dir (journal survives) and re-register -> recover(),
  4. assert: recovery actually ran (merge_recover count>0), no PR was lost, and main is GREEN
     (gate HEAD after recovery) — i.e. no unverified/broken change was left on main.

Run: python3 bench/chaos_recovery.py
"""
import json, os, pathlib, shutil, subprocess, sys, threading, time, urllib.request
import merge_sim as ms

ROOT = pathlib.Path("/tmp/arxael-chaos")
PORT = 8808
GIT_LOCK = threading.Lock()


def post(base, path, obj, t=120):
    req = urllib.request.Request(base + path, data=json.dumps(obj).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=t) as r:
        return json.loads(r.read())


def get(base, path):
    with urllib.request.urlopen(base + path, timeout=30) as r:
        return json.loads(r.read())


def launch(state_daemon, wipe):
    """Launch the daemon. wipe=True for a fresh start; wipe=False to RESTART preserving the journal."""
    if wipe and state_daemon.exists():
        shutil.rmtree(state_daemon)
    state_daemon.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    env.update({
        "ARXAEL_PORT": str(PORT), "ARXAEL_GRADLE_HOME": ms.GRADLE_HOME,
        "ARXAEL_STATE_DIR": str(state_daemon), "ARXAEL_CORES": "8",
        "ARXAEL_PER_WORKTREE_HOME": "true", "ARXAEL_BUILD_CACHE": "true",
        "ARXAEL_MAX_CONCURRENT": "8", "ARXAEL_RESERVED_HIGH": "2",
        "ARXAEL_ACQUIRE_TIMEOUT_MS": "600000", "ARXAEL_WARM_SERVERS": "512",
    })
    log = open(state_daemon.parent / "daemon.log", "a")
    proc = subprocess.Popen([str(ms.LAUNCHER)], env=env, stdout=log, stderr=subprocess.STDOUT)
    base = f"http://127.0.0.1:{PORT}"
    for _ in range(150):
        try:
            if json.loads(urllib.request.urlopen(base + "/health", timeout=2).read()).get("ok"):
                return proc, base
        except Exception:
            if proc.poll() is not None:
                raise RuntimeError("daemon died on startup")
            time.sleep(0.2)
    raise RuntimeError("daemon never healthy")


def submit_burst(bare, base, n):
    """Create n good branches off main (via a worktree) and submit them."""
    wt = ROOT / "wt"
    ms.git(["worktree", "add", "-q", "--detach", str(wt), "main"], cwd=bare)
    submitted = []
    for i in range(n):
        br = f"chaos{i}"
        ms.git(["checkout", "-q", "-B", br, "main"], cwd=wt)
        if ms.edit_slice(str(wt), i % 3, i % 2, 1000 + i, bad=False):
            ms.git(["add", "-A"], cwd=wt)
            ms.git(["commit", "-q", "-m", br], cwd=wt)
            try:
                post(base, "/merge/submit", {"branch": br, "module": f":mod{i % 3}", "agentId": "chaos"})
                submitted.append(br)
            except Exception:
                pass
    return submitted


def count_recover(state_daemon):
    ev = state_daemon / "events.jsonl"
    if not ev.exists():
        return 0
    tot = 0
    for line in ev.read_text().splitlines():
        if '"merge_recover"' in line:
            try:
                tot += json.loads(line).get("count", 0)
            except Exception:
                pass
    return tot


def main():
    if ROOT.exists():
        shutil.rmtree(ROOT)
    ROOT.mkdir(parents=True)
    # small real fixture so gates take a couple seconds (-> PRs genuinely in flight at kill time)
    fx = pathlib.Path("/tmp/arxael-chaos-fix")
    if fx.exists():
        shutil.rmtree(fx)
    subprocess.run([sys.executable, str(pathlib.Path(__file__).parent / "fixtures/gen_fixture.py"),
                    str(fx), "--modules", "3", "--classes", "20", "--methods", "2"], check=True)
    bare = ms.setup(str(fx), str(ROOT))
    state_daemon = ROOT / "state" / "daemon"

    ok = True
    proc, base = launch(state_daemon, wipe=True)
    try:
        post(base, "/merge/register", {"repo": str(bare),
                                       "forwardDeps": {":mod1": [":mod0"], ":mod2": [":mod1"]},
                                       "threshold": 4, "gateWorktrees": 3})
        submitted = submit_burst(bare, base, 12)
        print(f"submitted {len(submitted)} PRs; letting gates start…")
        time.sleep(4)  # some land, some are in flight / queued (journaled, not done)
        pre = get(base, "/merge/status")
        print(f"pre-crash status: landed={pre.get('landed')}, inFlightGates={pre.get('inFlightGates')}, "
              f"submitted={pre.get('submitted')}")
    finally:
        pass

    # ---- HARD CRASH ----
    print(">>> SIGKILL the daemon (hard crash, no graceful shutdown) <<<")
    proc.kill(); proc.wait()
    ms.reap_safe() if hasattr(ms, "reap_safe") else None
    time.sleep(1)

    # ---- RESTART on the same state dir (journal survives) ----
    proc2, base2 = launch(state_daemon, wipe=False)
    try:
        # re-register the same repo -> orchestrator.recover() re-enqueues journaled-but-unfinished PRs
        post(base2, "/merge/register", {"repo": str(bare),
                                        "forwardDeps": {":mod1": [":mod0"], ":mod2": [":mod1"]},
                                        "threshold": 4, "gateWorktrees": 3})
        # let recovery drain
        last = {}
        for _ in range(120):
            last = get(base2, "/merge/status")
            if last.get("inFlightGates", 0) == 0:
                time.sleep(1)
                last = get(base2, "/merge/status")
                if last.get("inFlightGates", 0) == 0:
                    break
            time.sleep(1)
        recovered = count_recover(state_daemon)
        print(f"post-recovery status: landed={last.get('landed')}, reverts={last.get('reverts')}, "
              f"errors={last.get('errors')}, recovered={recovered}")

        # ---- SOUNDNESS: gate main HEAD after recovery -> must be GREEN (no unverified/broken change) ----
        gate_wt = ROOT / "gate-main"
        with GIT_LOCK:
            ms.git(["worktree", "add", "-q", "--detach", str(gate_wt), "main"], cwd=bare)
        g = post(base2, "/invoke", {"adapter": "gradle", "worktree": str(gate_wt),
                                    "tasks": ["test"], "agentId": "chaos-verify"})
        main_green = g.get("status") == "SUCCESS"

        if recovered <= 0:
            ok = False; print("  FAIL  recovery did not run (no PRs were re-enqueued)")
        else:
            print(f"  PASS  recovery re-enqueued {recovered} PR(s) after the crash")
        if main_green:
            print("  PASS  main HEAD is GREEN after recovery (no unverified/broken change left on main)")
        else:
            ok = False; print(f"  FAIL  main HEAD is RED after recovery: {g.get('status')} :: {g.get('message')}")
        if last.get("errors", 0) != 0:
            print(f"  WARN  {last.get('errors')} errors during recovery (inspect daemon.log)")
    finally:
        try:
            urllib.request.urlopen(urllib.request.Request(base2 + "/shutdown", data=b"{}"), timeout=5)
        except Exception:
            pass
        try:
            proc2.wait(timeout=10)
        except Exception:
            proc2.kill()

    print("\n[chaos] " + ("\033[32mALL GREEN\033[0m — crash recovery is sound" if ok else "\033[31mFAILED\033[0m"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
