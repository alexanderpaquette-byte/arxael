#!/usr/bin/env python3
"""Per-language proof that the merge gate keeps `main` GREEN — run ONE language in ISOLATION, the way the
product is actually used: its own daemon on its own port, NO orphan-reaper (so it coexists with a supervised
service or anything else), clean teardown of only its own daemon. For each language it builds a real project,
starts the daemon with that language's gate adapter, registers it, then:

  - GOOD branch (a passing test) -> the orchestrator gates it with the language's test command -> LANDS.
  - BAD branch  (a failing test) -> gate red -> BOUNCED, main untouched.

Usage:  lang_merge_test.py <pytest|cargo|go|npm> [--port N]
Exit:   0 = PASS (or SKIP if the toolchain isn't installed), 1 = FAIL.
Run all of them isolated: bench/lang_merge_all.sh
(Maven + Gradle are proven on real OSS by realworld_oss.sh / realworld_caffeine.sh.)
"""
import json, os, pathlib, shutil, subprocess, sys, time, urllib.request
import merge_sim as ms


def have(cmd): return shutil.which(cmd) is not None


def post(base, path, obj, timeout=180):
    req = urllib.request.Request(base + path, data=json.dumps(obj).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r: return json.loads(r.read())


def get(base, path, timeout=30):
    with urllib.request.urlopen(base + path, timeout=timeout) as r: return json.loads(r.read())


def go_version():
    return subprocess.run(["go", "version"], capture_output=True, text=True).stdout.split()[2][2:] if have("go") else "1.21"


def langs():
    return {
        "pytest": dict(probe="pytest", adapter="pytest",
            base={"test_core.py": "def test_base():\n    assert 2 + 2 == 4\n"},
            good={"test_feature.py": "def test_feature():\n    assert 'arxael'.islower()\n"},
            bad={"test_broken.py": "def test_broken():\n    assert 1 == 2\n"}),
        "cargo": dict(probe="cargo", adapter="cargo",
            base={"Cargo.toml": "[package]\nname=\"arxdemo\"\nversion=\"0.1.0\"\nedition=\"2021\"\n",
                  "src/lib.rs": "pub fn id(x:i32)->i32{x}\n#[cfg(test)]\nmod t{#[test]fn base(){assert_eq!(super::id(2),2);}}\n"},
            good={"tests/good.rs": "#[test]\nfn good(){assert!(true);}\n"},
            bad={"tests/bad.rs": "#[test]\nfn bad(){assert_eq!(1,2);}\n"}),
        "go": dict(probe="go", adapter="go",
            base={"go.mod": f"module arxdemo\n\ngo {go_version()}\n",
                  "arx.go": "package arxdemo\n\nfunc Id(x int) int { return x }\n",
                  "arx_test.go": "package arxdemo\n\nimport \"testing\"\n\nfunc TestBase(t *testing.T){ if Id(2)!=2 { t.Fatal(\"id\") } }\n"},
            good={"feat_test.go": "package arxdemo\n\nimport \"testing\"\n\nfunc TestFeat(t *testing.T){ _ = Id(1) }\n"},
            bad={"broken_test.go": "package arxdemo\n\nimport \"testing\"\n\nfunc TestBroken(t *testing.T){ t.Fatal(\"boom\") }\n"}),
        "npm": dict(probe="node", adapter="npm",
            base={"package.json": "{\n  \"name\": \"arxdemo\",\n  \"version\": \"1.0.0\",\n  \"scripts\": { \"test\": \"node --test\" }\n}\n",
                  "base.test.js": "const t=require('node:test');const a=require('node:assert');\nt.test('base',()=>a.strictEqual(2+2,4));\n"},
            good={"feat.test.js": "const t=require('node:test');const a=require('node:assert');\nt.test('feat',()=>a.ok('arxael'));\n"},
            bad={"broken.test.js": "const t=require('node:test');const a=require('node:assert');\nt.test('broken',()=>a.strictEqual(1,2));\n"}),
    }


def start_daemon_noreap(port, state_root, adapter):
    """Start ONE daemon directly (Popen) — deliberately WITHOUT merge_sim.reap_orphan_daemons, which kills
    every arxael JVM on the box (it would nuke a supervised service). This coexists with anything running."""
    st = pathlib.Path(state_root)
    if st.exists(): shutil.rmtree(st)
    st.mkdir(parents=True)
    env = dict(os.environ)
    env.update({"ARXAEL_PORT": str(port), "ARXAEL_STATE_DIR": str(st / "daemon"),
                "ARXAEL_GRADLE_HOME": ms.GRADLE_HOME, "ARXAEL_MERGE_GATE_ADAPTER": adapter,
                "ARXAEL_CORES": "4", "ARXAEL_MAX_CONCURRENT": "4", "ARXAEL_RESERVED_HIGH": "1",
                "ARXAEL_PER_WORKTREE_HOME": "false"})
    proc = subprocess.Popen([str(ms.LAUNCHER)], env=env, start_new_session=True,
                            stdout=open(st / "daemon.log", "w"), stderr=subprocess.STDOUT)
    base = f"http://127.0.0.1:{port}"
    for _ in range(120):
        if proc.poll() is not None:
            raise RuntimeError(f"daemon exited early (rc={proc.returncode}); see {st}/daemon.log")
        try:
            urllib.request.urlopen(base + "/health", timeout=1); return proc, base
        except Exception:
            time.sleep(0.5)
    raise RuntimeError("daemon did not become healthy in 60s")


def commit_branch(wt, name, files):
    ms.git(["checkout", "-q", "-B", name, "main"], cwd=wt)
    for fn, body in files.items():
        p = pathlib.Path(wt) / fn; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(body)
    ms.git(["add", "-A"], cwd=wt)  # -A stages NEW files; `commit -a` would MISS them (a real by-hand footgun)
    ms.git(["commit", "-q", "-m", name], cwd=wt)


def pr_wait(base, branch, timeout):
    end = time.monotonic() + timeout
    last = {}
    while time.monotonic() < end:
        last = get(base, f"/merge/pr?branch={branch}")
        if last.get("terminal"): return last
        time.sleep(1)
    return last


def run(lang, port):
    cfg = langs()[lang]
    if not have(cfg["probe"]):
        print(f"[lang-test] {lang} SKIP (no {cfg['probe']} installed)"); return "skip"
    root = pathlib.Path(f"/tmp/lang-test-{lang}"); fx = pathlib.Path(f"/tmp/lang-test-fx-{lang}")
    for p in (root, fx):
        if p.exists(): shutil.rmtree(p)
    fx.mkdir(parents=True)
    for fn, body in cfg["base"].items():
        f = fx / fn; f.parent.mkdir(parents=True, exist_ok=True); f.write_text(body)
    bare = ms.setup(str(fx), str(root))
    proc, base = start_daemon_noreap(port, root / "state", cfg["adapter"])
    try:
        post(base, "/merge/register", {"repo": str(bare), "forwardDeps": {":app": []}, "gateWorktrees": 2})
        wt = root / "wt"; ms.git(["worktree", "add", "-q", "--detach", str(wt), "main"], cwd=bare)
        commit_branch(wt, "good", cfg["good"])
        post(base, "/merge/submit", {"branch": "good", "agentId": "lang-test"})
        g = pr_wait(base, "good", 180)
        commit_branch(wt, "bad", cfg["bad"])
        post(base, "/merge/submit", {"branch": "bad", "agentId": "lang-test"})
        b = pr_wait(base, "bad", 180)
        s = get(base, "/merge/status")
        good_ok = g.get("state") == "landed"
        bad_ok = b.get("state") in ("bounced", "reverted")
        main_green = s.get("landed", 0) == 1 and s.get("reverts", 0) == 0 and s.get("errors", 0) == 0
        ok = good_ok and bad_ok and main_green
        print(f"[lang-test] {lang} {'PASS' if ok else 'FAIL'}  "
              f"good={g.get('state')} bad={b.get('state')} landed={s.get('landed')} "
              f"bouncedSemantic={s.get('bouncedSemantic')} reverts={s.get('reverts')} errors={s.get('errors')}")
        return "pass" if ok else "fail"
    finally:
        try: urllib.request.urlopen(urllib.request.Request(base + "/shutdown", data=b"{}"), timeout=5)
        except Exception: pass
        try: proc.wait(timeout=10)
        except Exception: proc.kill()
        for p in (root, fx):
            shutil.rmtree(p, ignore_errors=True)


def main():
    args = sys.argv[1:]
    if not args or args[0] not in langs():
        print(f"usage: lang_merge_test.py <{'|'.join(langs())}> [--port N]"); sys.exit(2)
    lang = args[0]
    port = int(args[args.index("--port") + 1]) if "--port" in args else 8850
    try:
        r = run(lang, port)
    except Exception as e:
        print(f"[lang-test] {lang} FAIL  ({e})"); sys.exit(1)
    sys.exit(0 if r in ("pass", "skip") else 1)


if __name__ == "__main__":
    main()
