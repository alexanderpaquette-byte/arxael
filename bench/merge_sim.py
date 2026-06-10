#!/usr/bin/env python3
"""Phase-2 prototype: many agents on ONE project -> branch -> test -> PR -> merge to main.

Models the target workflow: N agents each branch off the
CURRENT main, edit a slice, (optionally test their branch), and submit a PR. A serial merge-queue
orchestrator merges each PR onto current main in an integration worktree, detects textual conflicts
(git merge fails), and INTEGRATION-TESTS the merged result before landing — branch-green is not enough
(two PRs green alone can break together = semantic conflict). Lands (advances main) if green, else bounces.

Git model: a shared BARE repo (main.git); agent + integration worktrees off it; landing = advance the
bare `main` ref. Test runner is pluggable (default: gradle on the tree w/ shared RO dep cache; prod
routes through the warm executor). Scorecard: PRs landed/min, time-to-land, textual vs semantic
conflicts, main-green (a landed merge that a later integration test finds broken), rework.
"""
import argparse, json, os, queue, random, shutil, subprocess, threading, time, pathlib, urllib.request
from concurrent.futures import ThreadPoolExecutor

GRADLE = os.environ.get("ARXAEL_GRADLE_HOME", "/opt/gradle/gradle-8.10.2") + "/bin/gradle"
GRADLE_HOME = os.environ.get("ARXAEL_GRADLE_HOME", "/opt/gradle/gradle-8.10.2")
HERE = pathlib.Path(__file__).resolve().parent
LAUNCHER = HERE.parent / "core/build/install/core/bin/core"
GIT_LOCK = threading.Lock()   # serialize ref/worktree-mutating git ops (prototype-simple)

def sh(cmd, cwd=None, env=None, timeout=900):
    return subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True, timeout=timeout)
def git(args, cwd, **kw):
    return sh(["git"] + args, cwd=cwd, **kw)
def rev(ref, repo):
    return git(["rev-parse", ref], cwd=repo).stdout.strip()

# ---------------------------------------------------------------- repo setup
def setup(fixture, root):
    root = pathlib.Path(root)
    if root.exists(): shutil.rmtree(root)
    root.mkdir(parents=True)
    seed = root / "seed"; shutil.copytree(fixture, seed)
    git(["init", "-q", "-b", "main"], cwd=seed)
    for k, v in [("user.email", "sim@arxael"), ("user.name", "sim")]:
        git(["config", k, v], cwd=seed)
    (seed / ".gitignore").write_text("build/\n.gradle/\n.guh/\n*/build/\n")
    git(["add", "-A"], cwd=seed); git(["commit", "-q", "-m", "init"], cwd=seed)
    bare = root / "main.git"
    sh(["git", "clone", "-q", "--bare", str(seed), str(bare)])
    # bare repo needs an identity for merge commits made via worktrees
    for k, v in [("user.email", "sim@arxael"), ("user.name", "sim")]:
        git(["config", k, v], cwd=bare)
    shutil.rmtree(seed)
    return bare

# ---------------------------------------------------------------- agent edit
def edit_slice(worktree, midx, m, tag, bad=False):
    """Mutate method f{m} of module {midx}. Normal: change its constant (clean). bad=True: make it
    return 0 -> its test t{m} fails (an agent whose PR doesn't pass). Caller picks midx/m (so PRs can
    be spread across modules for the disjoint fast-path, or forced onto mod0 for conflicts)."""
    f = pathlib.Path(worktree) / f"mod{midx}" / "src/main/java/dev/arxael/bench" / f"mod{midx}" / "C0.java"
    if not f.exists():
        f = pathlib.Path(worktree) / "src/main/java/dev/arxael/bench" / "C0.java"
    if not f.exists():
        return None
    src = f.read_text()
    if bad:
        # break method f{m}: make it return 0 (its generated test asserts != 0 -> red)
        rets = [k for k in range(len(src)) if src.startswith("return a + mix(x);", k)]
        if m < len(rets):
            k = rets[m]; src = src[:k] + "return 0;" + src[k + len("return a + mix(x);"):]
        else:
            return None
    else:
        needle = "long a = x + "
        i = -1
        for _ in range(m + 1):
            i = src.find(needle, i + 1)
            if i < 0: break
        if i < 0:
            i = src.find(needle)
            if i < 0: return None
        j = src.find(";", i)
        src = src[:i] + f"{needle}{tag}" + src[j:]
    f.write_text(src)
    return str(f)

# ---------------------------------------------------------------- test runner (pluggable)
def gradle_test(tree, ro_dep_cache, label, logdir):
    home = pathlib.Path(tree) / ".guh"
    env = dict(os.environ)
    if ro_dep_cache: env["GRADLE_RO_DEP_CACHE"] = ro_dep_cache
    # Cap the daemon idle timeout (mirrors the core adapter's ARXAEL_DAEMON_IDLE_SEC): each per-worktree
    # .guh home spawns its own daemon, and gradle's 3h default leaks dozens of GB-each daemons across a
    # run on a pay-per-use box. 60s idle dies them shortly after the run without thrashing warm reuse.
    r = sh([GRADLE, "-p", str(tree), "--gradle-user-home", str(home), "-q", "--offline",
            "-Dorg.gradle.daemon.idletimeout=60000",
            "--parallel", "--max-workers=2", "--rerun-tasks", "test"], env=env)
    if logdir:
        (pathlib.Path(logdir) / f"{label}.log").write_text((r.stdout or "")[-4000:] + (r.stderr or "")[-4000:])
    return r.returncode == 0

# ---- warm-executor path: branch-tests AND merge-gate tests share ONE warm substrate (the real
#      architecture). They compete for the executor's bounded concurrency = the realistic mixed load
#      (agents testing WHILE the queue merges — never clean).
def start_daemon(port, state, cores, ro_cache, max_concurrent, build_cache=False, reserved_high=0):
    st = pathlib.Path(state);
    if st.exists(): shutil.rmtree(st)
    st.mkdir(parents=True)
    env = dict(os.environ)
    env.update({
        "ARXAEL_PORT": str(port), "ARXAEL_GRADLE_HOME": GRADLE_HOME, "ARXAEL_STATE_DIR": str(st / "daemon"),
        "ARXAEL_CORES": str(cores),
        # default ON, but allow an A/B override so we can measure the flip's value vs the shared home
        "ARXAEL_PER_WORKTREE_HOME": os.environ.get("ARXAEL_PER_WORKTREE_HOME", "true"),
        "ARXAEL_BUILD_CACHE": "true" if build_cache else "false",
        "ARXAEL_BUILD_WORKERS": "2", "ARXAEL_WARM_SERVERS": "512", "ARXAEL_ACQUIRE_TIMEOUT_MS": "600000",
        "ARXAEL_MAX_CONCURRENT": str(max_concurrent), "ARXAEL_RESERVED_HIGH": str(reserved_high),
    })
    if ro_cache: env["GRADLE_RO_DEP_CACHE"] = ro_cache
    proc = subprocess.Popen([str(LAUNCHER)], env=env,
                            stdout=open(st / "daemon.log", "w"), stderr=subprocess.STDOUT)
    base = f"http://127.0.0.1:{port}"
    for _ in range(150):
        try:
            with urllib.request.urlopen(base + "/health", timeout=2) as r:
                if json.loads(r.read()).get("ok"): return proc, base
        except Exception:
            if proc.poll() is not None: raise RuntimeError("daemon died on startup")
            time.sleep(0.2)
    raise RuntimeError("daemon never healthy")

import re as _re
def parse_failed_modules(output):
    """Heuristic: which modN's tests FAILED, from the build output. Returns a set of module indices, or
    None if it failed but no module could be attributed (-> caller treats all as suspect, stays safe)."""
    if not output:
        return None
    mods = set()
    for m in _re.finditer(r":mod(\d+):\w+\s+FAILED", output):                 # "> Task :mod3:test FAILED"
        mods.add(int(m.group(1)))
    for m in _re.finditer(r"Execution failed for task '?:mod(\d+):", output): # the failure summary (always at tail)
        mods.add(int(m.group(1)))
    return mods or None   # ONLY failure-context (not every '--info' task line — that flagged all modules)

def invoke_gate(base, worktree, label, logdir, rerun=False):
    """Like invoke_test but returns (success, failed_modules) — for module-attribution. Incremental by
    default (rerun=False) so only the merge's changed modules test and the failed module names the culprit."""
    args = (["--rerun-tasks"] if rerun else [])   # default console: "Execution failed for task ':modN:'" is in the tail
    body = json.dumps({"adapter": "gradle", "worktree": str(worktree),
                       "tasks": ["test"], "args": args, "agentId": "gate", "priority": "high"}).encode()
    try:
        req = urllib.request.Request(base + "/invoke", data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=900) as r:
            o = json.loads(r.read())
        ok = o.get("status") == "SUCCESS"
        failed = None if ok else parse_failed_modules(o.get("output", ""))
        if logdir and not ok:
            (pathlib.Path(logdir) / f"{label}.log").write_text((o.get("output", "") or "")[-6000:])
        return ok, failed
    except Exception as e:
        if logdir: (pathlib.Path(logdir) / f"{label}.err").write_text(repr(e))
        return False, None

def build_revdeps(fixture, n_modules):
    """Reverse dependency map: module -> set of modules that DEPEND on it (parse `project(":modK")`)."""
    rev = {i: set() for i in range(n_modules)}
    for j in range(n_modules):
        bg = pathlib.Path(fixture) / f"mod{j}" / "build.gradle.kts"
        if not bg.exists(): continue
        for m in _re.finditer(r'project\(":mod(\d+)"\)', bg.read_text()):
            rev[int(m.group(1))].add(j)        # modj depends on modK -> modK's dependent is modj
    return rev
def affected_closure(module, rev):
    """Modules whose tests a change to `module` could break = module + transitive dependents."""
    seen, stack = {module}, [module]
    while stack:
        for d in rev.get(stack.pop(), ()):
            if d not in seen: seen.add(d); stack.append(d)
    return seen

def invoke_module_test(base, worktree, modules, label, logdir):
    """Async-gate the PR's module + its transitive DEPENDENTS (sound for real dependency graphs; for
    independent modules this is just the one module). A bad module fails only its own affected-set's
    gate, never poisoning unrelated modules -> no cascade."""
    tasks = [f":mod{m}:test" for m in sorted(modules)]
    body = json.dumps({"adapter": "gradle", "worktree": str(worktree),
                       "tasks": tasks, "args": ["--rerun-tasks", "--quiet"],
                       "agentId": "modgate", "priority": "high"}).encode()
    try:
        req = urllib.request.Request(base + "/invoke", data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=900) as r:
            return json.loads(r.read()).get("status") == "SUCCESS"
    except Exception as e:
        if logdir: (pathlib.Path(logdir) / f"{label}.err").write_text(repr(e))
        return False

def invoke_test(base, worktree, label, logdir, rerun=True, priority="normal"):
    # rerun=True: --rerun-tasks (full retest, the safe baseline). rerun=False: incremental — gradle's
    # up-to-date + build-cache re-test ONLY the modules the merge changed (cheap affected-module gate).
    # priority="high" -> reserved executor lane (merge-gate tests don't get starved by branch-tests).
    args = (["--rerun-tasks"] if rerun else []) + ["--quiet"]
    body = json.dumps({"adapter": "gradle", "worktree": str(worktree),
                       "tasks": ["test"], "args": args, "agentId": "merge", "priority": priority}).encode()
    try:
        req = urllib.request.Request(base + "/invoke", data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=900) as r:
            o = json.loads(r.read())
        if logdir and o.get("status") != "SUCCESS":
            (pathlib.Path(logdir) / f"{label}.log").write_text(json.dumps(o)[:4000])
        return o.get("status") == "SUCCESS"
    except Exception as e:
        if logdir: (pathlib.Path(logdir) / f"{label}.err").write_text(repr(e))
        return False

# ---------------------------------------------------------------- merge queue (serial)
class MergeQueue(threading.Thread):
    def __init__(self, bare, intg, ro, logdir, strategy="serial", batch=4, tester=None, disjoint_fastpath=False,
                 gate_modules=None, module_tester=None):
        super().__init__(daemon=True)
        self.bare, self.intg, self.ro, self.logdir = str(bare), str(intg), ro, logdir
        self.strategy, self.batch = strategy, batch
        self.cur_batch = batch                 # adaptive (smart): grows clean, shrinks on red (AIMD)
        self.max_batch = max(batch * 4, batch)
        self.tester = tester   # callable(tree, label) -> bool; warm-executor or direct gradle
        self.gate_modules = gate_modules  # callable(tree,label)->(bool, failed_modules|None); attribute strategy
        self.module_tester = module_tester  # callable(tree,module,label)->bool; optimistic module-scoped async gate
        self.disjoint_fastpath = disjoint_fastpath
        self.q = queue.Queue(); self.lock = threading.Lock(); self.stop = False
        self.gate_wts = queue.Queue()                              # optimistic/auto: pool of async-gate worktrees
        self.gate_pool = ThreadPoolExecutor(max_workers=8) if strategy in ("optimistic", "auto") else None
        self.inflight = []
        self.revdeps = {}            # module -> dependents (for auto-route closure sizing)
        self.auto_threshold = 4      # closure <= this => optimistic (fast); larger => batched (sound)
        self.m = dict(submitted=0, landed=0, bounced_textual=0, bounced_semantic=0, integ_tests=0,
                      bisect_tests=0, culprits=0, fastpath_landed=0, reverts=0, broken_window_s=[],
                      batch_sizes=[], ttl=[],
                      # auto-route visibility: how each landing was routed (small-closure optimistic vs batched).
                      opt_landed=0, batch_landed=0)
    def submit(self, pr):
        with self.lock: self.m["submitted"] += 1
        self.q.put(pr)
    def run(self):
        while not self.stop:                       # stop => quit pulling new PRs (don't drain backlog)
            try: pr = self.q.get(timeout=1)
            except queue.Empty: continue
            prs = [pr]
            if self.strategy in ("batch", "smart", "attribute", "auto"):   # speculatively grab queued PRs
                cap = self.cur_batch if self.strategy in ("smart", "attribute") else self.batch
                while len(prs) < cap:
                    try: prs.append(self.q.get_nowait())
                    except queue.Empty: break
            with self.lock: self.m["batch_sizes"].append(len(prs))
            try:
                if self.strategy == "smart": self._process_smart(prs)
                elif self.strategy == "attribute": self._process_attribute(prs)
                elif self.strategy == "optimistic": self._process_optimistic(prs)
                elif self.strategy == "auto": self._process_auto(prs)
                else: self._process(prs)
            except Exception as e:
                (pathlib.Path(self.logdir)/f"err-{prs[0]['branch']}.log").write_text(repr(e))
            for _ in prs: self.q.task_done()
    def _process(self, prs):
        """Merge prs onto current main in the integration tree, ONE integration test for the whole set;
        land all if green. On a red batch (>1), fall back to per-PR serial to find the good ones."""
        # DISJOINT FAST-PATH: PRs touching distinct INDEPENDENT modules, each already branch-tested
        # green, cannot interact -> land them with NO integration test (the cheapest possible gate).
        if self.disjoint_fastpath and len(prs) > 1:
            mods = [pr.get("module") for pr in prs]
            if all(mm is not None for mm in mods) and len(set(mods)) == len(mods):
                merged = []
                with GIT_LOCK:
                    git(["checkout", "-q", "--detach", "main"], cwd=self.intg)
                    git(["reset", "-q", "--hard", "main"], cwd=self.intg)
                    for pr in prs:
                        mr = git(["merge", "--no-ff", "-m", f"m {pr['branch']}", pr["branch"]], cwd=self.intg)
                        if mr.returncode != 0:
                            git(["merge", "--abort"], cwd=self.intg)
                            with self.lock: self.m["bounced_textual"] += 1
                        else: merged.append(pr)
                    if merged:
                        head = rev("HEAD", self.intg)
                        git(["branch", "-f", "main", head], cwd=self.bare)
                with self.lock:
                    self.m["landed"] += len(merged); self.m["fastpath_landed"] += len(merged)
                    for pr in merged: self.m["ttl"].append(time.monotonic() - pr["t"])
                return
        merged = []
        with GIT_LOCK:
            git(["checkout", "-q", "--detach", "main"], cwd=self.intg)
            git(["reset", "-q", "--hard", "main"], cwd=self.intg)
            for pr in prs:
                mr = git(["merge", "--no-ff", "-m", f"merge {pr['branch']}", pr["branch"]], cwd=self.intg)
                if mr.returncode != 0:
                    git(["merge", "--abort"], cwd=self.intg)
                    with self.lock: self.m["bounced_textual"] += 1   # textual conflict vs main+prior
                else:
                    merged.append(pr)
            if not merged: return
            head = rev("HEAD", self.intg)
        with self.lock: self.m["integ_tests"] += 1
        green = self.tester(self.intg, f"integ-{merged[0]['branch']}-x{len(merged)}")
        if green:
            with GIT_LOCK:
                git(["branch", "-f", "main", head], cwd=self.bare)   # land the whole batch
            with self.lock:
                self.m["landed"] += len(merged); self.m["batch_landed"] += len(merged)
                for pr in merged: self.m["ttl"].append(time.monotonic() - pr["t"])
        else:
            if len(merged) == 1:
                with self.lock:
                    self.m["bounced_semantic"] += 1; self.m["culprits"] += 1   # pinpointed: this PR failed alone
                (pathlib.Path(self.logdir) / f"culprit-{merged[0]['branch']}.txt").write_text(
                    f"culprit={merged[0]['branch']} failed the integration test alone")
            else:
                # red batch -> re-test each PR ALONE: lands all the good ones, pinpoints EVERY bad one
                # (better than prefix-bisection, which finds only the first culprit and churns on re-queue).
                for pr in merged: self._process([pr])

    # ---- smart strategy: adaptive batch size (AIMD) + bisection that pinpoints the exact culprit ----
    def _adapt(self, success):
        # bisection makes a red train cheap (log-N, still lands the green prefix), so DON'T collapse the
        # batch on red — keep it large for throughput and let bisection isolate the culprit. Grow on
        # green; shrink only gently on red (guards against a pathological all-bad burst).
        with self.lock:
            if success: self.cur_batch = min(self.max_batch, self.cur_batch + 2)
            else:       self.cur_batch = max(4, self.cur_batch - 1)
    def _test_at(self, commit, label):
        with GIT_LOCK:
            git(["checkout", "-q", "--detach", commit], cwd=self.intg)
        return self.tester(self.intg, label)
    def _longest_green_prefix(self, prefix):
        # full train (len) known red, main (0) green; binary-search largest g with prefix[0..g-1] green
        lo, hi = 0, len(prefix)
        while hi - lo > 1:
            mid = (lo + hi) // 2
            with self.lock: self.m["bisect_tests"] += 1
            if self._test_at(prefix[mid-1][1], f"bisect-m{mid}"): lo = mid
            else: hi = mid
        return lo
    def _process_smart(self, prs):
        prefix = []                                  # [(pr, commit_after_merge)] in train order
        with GIT_LOCK:
            git(["checkout", "-q", "--detach", "main"], cwd=self.intg)
            git(["reset", "-q", "--hard", "main"], cwd=self.intg)
            for pr in prs:
                mr = git(["merge", "--no-ff", "-m", f"m {pr['branch']}", pr["branch"]], cwd=self.intg)
                if mr.returncode != 0:
                    git(["merge", "--abort"], cwd=self.intg)
                    with self.lock: self.m["bounced_textual"] += 1
                else:
                    prefix.append((pr, rev("HEAD", self.intg)))
        if not prefix: self._adapt(False); return
        with self.lock: self.m["integ_tests"] += 1
        if self.tester(self.intg, f"smart-full-x{len(prefix)}"):     # whole train green -> land all + grow
            with GIT_LOCK: git(["branch", "-f", "main", prefix[-1][1]], cwd=self.bare)
            with self.lock:
                self.m["landed"] += len(prefix)
                for pr, _ in prefix: self.m["ttl"].append(time.monotonic() - pr["t"])
            self._adapt(True); return
        g = self._longest_green_prefix(prefix)                       # pinpoint: PR[g] is the culprit
        if g > 0:
            with GIT_LOCK: git(["branch", "-f", "main", prefix[g-1][1]], cwd=self.bare)
            with self.lock:
                self.m["landed"] += g
                for pr, _ in prefix[:g]: self.m["ttl"].append(time.monotonic() - pr["t"])
        with self.lock:
            self.m["bounced_semantic"] += 1; self.m["culprits"] += 1
        (pathlib.Path(self.logdir)/f"culprit-{prefix[g][0]['branch']}.txt").write_text(
            f"culprit={prefix[g][0]['branch']} broke the train after {g} clean PRs (of {len(prefix)})")
        for pr, _ in prefix[g+1:]: self.q.put(pr)                    # re-queue the rest vs the new main
        self._adapt(False)

    # ---- attribute strategy: incremental gate names the failed MODULE -> the PR that touched it is the
    #      culprit; land the rest. SOUND via a confirm re-test of the good set before landing. ----
    def _merge_onto_main(self, prs):
        merged = []
        with GIT_LOCK:
            git(["checkout", "-q", "--detach", "main"], cwd=self.intg)
            git(["reset", "-q", "--hard", "main"], cwd=self.intg)
            for pr in prs:
                mr = git(["merge", "--no-ff", "-m", f"m {pr['branch']}", pr["branch"]], cwd=self.intg)
                if mr.returncode != 0:
                    git(["merge", "--abort"], cwd=self.intg)
                    with self.lock: self.m["bounced_textual"] += 1
                else: merged.append(pr)
            head = rev("HEAD", self.intg) if merged else None
        return merged, head
    def _land(self, prs, head):
        with GIT_LOCK: git(["branch", "-f", "main", head], cwd=self.bare)
        with self.lock:
            self.m["landed"] += len(prs)
            for pr in prs: self.m["ttl"].append(time.monotonic() - pr["t"])
    def _process_attribute(self, prs):
        merged, head = self._merge_onto_main(prs)
        if not merged: self._adapt(False); return
        with self.lock: self.m["integ_tests"] += 1
        green, failed = self.gate_modules(self.intg, f"attr-full-x{len(merged)}")
        if green:
            self._land(merged, head); self._adapt(True); return
        # red: attribute the failure to module(s) -> culprit PR(s); unknown parse => all suspect (stay safe)
        suspect = failed if failed else {pr["module"] for pr in merged}
        good = [pr for pr in merged if pr["module"] not in suspect]
        culprits = [pr for pr in merged if pr["module"] in suspect]
        if good and len(good) < len(merged):
            gmerged, ghead = self._merge_onto_main(good)        # CONFIRM the good set is really green
            with self.lock: self.m["integ_tests"] += 1
            green2, _ = self.gate_modules(self.intg, f"attr-confirm-x{len(gmerged)}")
            if green2 and gmerged:
                self._land(gmerged, ghead)
                for pr in culprits:
                    with self.lock: self.m["bounced_semantic"] += 1; self.m["culprits"] += 1
                    (pathlib.Path(self.logdir)/f"culprit-{pr['branch']}.txt").write_text(
                        f"culprit={pr['branch']} (module mod{pr['module']}) — its test failed the incremental gate")
                self._adapt(False); return
        # attribution unusable (parse failed, all-suspect, or confirm red) -> safe per-PR fallback
        for pr in merged: self._process([pr])
        self._adapt(False)

    # ---- optimistic strategy (my design): land immediately, verify async, auto-revert a break.
    #      Land-latency decouples from gate cost; cost is a brief auto-reverted broken-main window. ----
    def _process_optimistic(self, prs):
        for pr in prs:
            merged, head = self._merge_onto_main([pr])
            if not merged: continue
            with GIT_LOCK: git(["branch", "-f", "main", head], cwd=self.bare)   # LAND now (branch already green)
            with self.lock:
                self.m["landed"] += 1; self.m["opt_landed"] += 1; self.m["ttl"].append(time.monotonic() - pr["t"])
            self.inflight.append(self.gate_pool.submit(self._async_gate, pr, head, time.monotonic()))
    def _async_gate(self, pr, commit, land_t):
        wt = self.gate_wts.get()
        try:
            with GIT_LOCK:
                git(["checkout", "-q", "--detach", commit], cwd=wt); git(["reset", "-q", "--hard", commit], cwd=wt)
            with self.lock: self.m["integ_tests"] += 1
            # MODULE-SCOPED async gate: test only the PR's module so a bad module can't poison others' gates
            if self.module_tester(wt, pr.get("module"), f"opt-gate-{pr['branch']}"):
                return                                                   # this module's test passed -> land was correct
            with GIT_LOCK:                                              # red -> AUTO-REVERT the bad merge from main
                git(["checkout", "-q", "-B", "__rev", "main"], cwd=wt)
                r = git(["revert", "--no-edit", "-m", "1", commit], cwd=wt)
                ok = r.returncode == 0
                if ok: git(["branch", "-f", "main", rev("HEAD", wt)], cwd=self.bare)
                else: git(["revert", "--abort"], cwd=wt)
            with self.lock:
                self.m["bounced_semantic"] += 1
                if ok:
                    self.m["reverts"] += 1; self.m["landed"] -= 1
                    self.m["broken_window_s"].append(time.monotonic() - land_t)
                    (pathlib.Path(self.logdir) / f"revert-{pr['branch']}.txt").write_text(
                        f"auto-reverted {pr['branch']} (mod{pr.get('module')}) after async gate red; "
                        f"main was broken {time.monotonic()-land_t:.0f}s")
        finally:
            self.gate_wts.put(wt)

    # ---- auto-route (the meta-strategy): per PR, small dependency-closure -> optimistic (fast, instant);
    #      large closure (chain-like) -> batched gate-then-land (sound, never breaks main). Best of both. ----
    def _process_auto(self, prs):
        small = [pr for pr in prs if len(affected_closure(pr.get("module", 0), self.revdeps)) <= self.auto_threshold]
        large = [pr for pr in prs if pr not in small]
        for pr in small: self._process_optimistic([pr])   # land now + module-scoped async verify
        if large: self._process(large)                    # synchronous batched gate-then-land

# ---------------------------------------------------------------- agents
def agent_loop(aid, bare, mq, args, deadline):
    rnd = random.Random(aid * 7919)
    root = pathlib.Path(bare).parent
    # STABLE per-agent worktree (real agents reuse their working copy) -> the warm server for this path
    # is reused across PRs (warm daemon), instead of a cold daemon per PR. Branch off CURRENT main each time.
    wt = root / f"wt-agent{aid}"
    with GIT_LOCK:
        git(["worktree", "add", "-q", "--detach", str(wt), "main"], cwd=bare)
    while time.monotonic() < deadline:
        br = f"a{aid}-{int(time.monotonic()*1000)%1000000}"
        with GIT_LOCK:
            r = git(["checkout", "-q", "-B", br, "main"], cwd=wt)   # reuse worktree; fresh branch off current main
        if r.returncode != 0:
            time.sleep(0.5); continue
        try:
            conflict = args.conflict > 0 and rnd.random() < args.conflict
            bad = args.bad_rate > 0 and rnd.random() < args.bad_rate
            tag = (aid * 2654435761 + rnd.randint(0, 1 << 20)) & 0xFFFFFF
            midx = 0 if conflict else (tag % max(1, args.modules))      # spread PRs across modules
            m = 0 if conflict else ((tag // 97) % max(1, args.methods))
            if edit_slice(wt, midx, m, tag, bad):
                git(["add", "-A"], cwd=wt); git(["commit", "-q", "-m", f"{br}"], cwd=wt)
                if not args.gate_branch or args.tester(wt, f"branch-{br}"):
                    mq.submit({"branch": br, "t": time.monotonic(), "module": midx})
        except Exception:
            pass
        time.sleep(random.expovariate(args.rate))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixture", default="/tmp/bench-mm24")
    ap.add_argument("--modules", type=int, default=24)
    ap.add_argument("--methods", type=int, default=6, help="methods/class in the fixture (edit-target spread)")
    ap.add_argument("--agents", type=int, default=8)
    ap.add_argument("--rate", type=float, default=0.15, help="per-agent PR submit rate (Poisson, /s)")
    ap.add_argument("--conflict", type=float, default=0.0, help="prob an edit targets the shared file")
    ap.add_argument("--bad-rate", type=float, default=0.0, dest="bad_rate",
                    help="prob a PR makes a BREAKING edit (fails its test) — exercises the gate + culprit isolation")
    ap.add_argument("--gate-branch", action="store_true")
    ap.add_argument("--auto-threshold", type=int, default=4, dest="auto_threshold",
                    help="auto strategy: PRs with dependency-closure <= this go optimistic, larger go batched")
    ap.add_argument("--strategy", choices=["serial", "batch", "smart", "attribute", "optimistic", "auto"], default="batch",
                    help="batch (full-test + re-test-each-on-red); attribute (incremental gate names the "
                         "failed module -> land the rest, bounce the culprit, confirm-re-tested = sound); "
                         "serial (1/test); smart (prefix-bisection — REJECTED)")
    ap.add_argument("--batch", type=int, default=8, help="batch cap (serial=1; smart adapts up to 4x this)")
    ap.add_argument("--disjoint-fastpath", action="store_true", dest="disjoint_fastpath",
                    help="land branch-gated PRs touching distinct independent modules WITHOUT a combined "
                         "integration test (sound only with --independent fixture + --gate-branch)")
    ap.add_argument("--ro-dep-cache", default="/tmp/ro-seed-home/caches")
    ap.add_argument("--executor", action="store_true",
                    help="route ALL tests (branch + merge-gate) through ONE warm executor (the real arch)")
    ap.add_argument("--cores", type=int, default=os.cpu_count())
    ap.add_argument("--max-concurrent", type=int, default=0, help="executor bound (0 = cores)")
    ap.add_argument("--reserved-high", type=int, default=0, dest="reserved_high",
                    help="executor permits reserved for merge-gate (high) tests so branch-tests can't starve them")
    ap.add_argument("--incremental-gate", action="store_true",
                    help="integration test runs INCREMENTALLY (build-cache + up-to-date) -> retest only the "
                         "modules the merge changed, not a full --rerun-tasks. Big gate-cost reduction.")
    ap.add_argument("--port", type=int, default=8790)
    ap.add_argument("--window", type=float, default=180)
    ap.add_argument("--root", default="/tmp/arxmerge")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    bare = setup(args.fixture, args.root)
    logdir = pathlib.Path(args.root) / "logs"; logdir.mkdir(exist_ok=True); args.logdir = str(logdir)
    intg = pathlib.Path(args.root) / "integration"
    git(["worktree", "add", "-q", "--detach", str(intg), "main"], cwd=bare)

    # Test runner: ONE warm executor for branch + merge-gate tests (real arch), or direct gradle.
    daemon = None; base = None
    attribute = args.strategy == "attribute"
    gate_modules = None
    if args.executor:
        mc = args.max_concurrent or args.cores
        build_cache = args.incremental_gate or attribute          # attribute needs incremental for module names
        daemon, base = start_daemon(args.port, str(pathlib.Path(args.root) / "exec"), args.cores,
                                    args.ro_dep_cache, mc, build_cache=build_cache,
                                    reserved_high=args.reserved_high)
        rerun = not args.incremental_gate
        tester_gate   = lambda tree, label: invoke_test(base, tree, label, args.logdir, rerun=rerun, priority="high")
        tester_branch = lambda tree, label: invoke_test(base, tree, label, args.logdir, rerun=rerun, priority="normal")
        gate_modules  = lambda tree, label: invoke_gate(base, tree, label, args.logdir, rerun=False)  # incremental
        revdeps = build_revdeps(args.fixture, args.modules)   # module -> dependents, for dep-aware scoping
        module_tester = lambda tree, mod, label: invoke_module_test(
            base, tree, affected_closure(mod, revdeps), label, args.logdir)
    else:
        tester_gate = tester_branch = lambda tree, label: gradle_test(tree, args.ro_dep_cache, label, args.logdir)
        gate_modules = lambda tree, label: (gradle_test(tree, args.ro_dep_cache, label, args.logdir), None)
        module_tester = lambda tree, mod, label: gradle_test(tree, args.ro_dep_cache, label, args.logdir)
    args.tester = tester_branch                                  # agents' branch tests = normal lane

    mq = MergeQueue(bare, intg, args.ro_dep_cache, args.logdir, args.strategy, args.batch, tester_gate,
                    args.disjoint_fastpath, gate_modules, module_tester)
    if args.strategy in ("optimistic", "auto"):                        # pool of async-gate worktrees
        for k in range(8):
            gw = pathlib.Path(args.root) / f"gate{k}"
            git(["worktree", "add", "-q", "--detach", str(gw), "main"], cwd=bare)
            mq.gate_wts.put(str(gw))
    if args.strategy == "auto":
        mq.revdeps = build_revdeps(args.fixture, args.modules); mq.auto_threshold = args.auto_threshold
    mq.start()   # merge-gate tests = high lane
    deadline = time.monotonic() + args.window
    agents = [threading.Thread(target=agent_loop, args=(i, bare, mq, args, deadline), daemon=True)
              for i in range(args.agents)]
    t0 = time.monotonic()
    for a in agents: a.start()
    for a in agents: a.join()
    # Don't drain the backlog (serial queue can be far behind = the finding). Let the in-flight merge
    # finish (bounded grace), then stop. Steady-state land-rate = landed / window; backlog = the rest.
    mq.stop = True; mq.join(timeout=max(60, args.window))
    if mq.gate_pool:                                   # optimistic: let async gates (+ reverts) finish
        for f in list(mq.inflight):
            try: f.result(timeout=300)
            except Exception: pass
        mq.gate_pool.shutdown(wait=True)
    elapsed = time.monotonic() - t0

    ttl = sorted(mq.m["ttl"])
    submitted = mq.m["submitted"]; landed = mq.m["landed"]
    bounced = mq.m["bounced_textual"] + mq.m["bounced_semantic"]
    res = {"agents": args.agents, "rate": args.rate, "conflict": args.conflict, "elapsed_s": round(elapsed, 1),
           "submitted": submitted, "landed": landed, "bounced_textual": mq.m["bounced_textual"],
           "bounced_semantic": mq.m["bounced_semantic"], "integ_tests": mq.m["integ_tests"],
           "backlog": max(0, submitted - landed - bounced),
           "merges_per_min": round(landed/elapsed*60, 1) if elapsed else 0,
           "land_rate_vs_submit": round(landed/submitted, 2) if submitted else 0,
           "integ_tests": mq.m["integ_tests"], "bisect_tests": mq.m["bisect_tests"],
           "tests_per_landed": round((mq.m["integ_tests"]+mq.m["bisect_tests"])/landed, 2) if landed else 0,
           "culprits_isolated": mq.m["culprits"], "fastpath_landed": mq.m["fastpath_landed"],
           "opt_landed": mq.m["opt_landed"], "batch_landed": mq.m["batch_landed"],  # auto-route split
           "reverts": mq.m["reverts"],
           "median_broken_window_s": round(sorted(mq.m["broken_window_s"])[len(mq.m["broken_window_s"])//2], 1) if mq.m["broken_window_s"] else 0,
           "strategy": args.strategy,
           "avg_batch": round(sum(mq.m["batch_sizes"])/len(mq.m["batch_sizes"]), 1) if mq.m["batch_sizes"] else 0,
           "final_cur_batch": mq.cur_batch,
           "p50_ttl_s": round(ttl[len(ttl)//2], 1) if ttl else 0, "max_ttl_s": round(ttl[-1], 1) if ttl else 0}
    print(json.dumps(res, indent=2))
    if args.out:
        with open(args.out, "a") as f: f.write(json.dumps(res) + "\n")

    if daemon:
        try: urllib.request.urlopen(urllib.request.Request(base + "/shutdown", data=b"{}"), timeout=5)
        except Exception: pass
        time.sleep(0.5); daemon.terminate()
        try: daemon.wait(timeout=10)
        except Exception: daemon.kill()

if __name__ == "__main__":
    main()
