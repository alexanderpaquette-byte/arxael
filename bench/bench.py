#!/usr/bin/env python3
"""Deliverable #1 — one benchmark cell: (arm, agents, cores) on this box.

Two arms, identical fixture build as the unit of work, closed-loop agents:

  warm       : one arxael daemon (warm bounded executor); M agents each POST /invoke against
               their own worktree. Concurrency is capped by the executor; excess agents queue.
               Memory is bounded (~C gradle daemons reused across worktrees).
  container  : M long-lived containers, each a gradle daemon warm WITHIN its life (the FAIR
               steelman), each holding its own daemon+cache footprint. Memory
               grows ~linearly with M -> the collapse arm.

Measures, over a steady-state window (after warmup): goodput (tasks/min), p95 latency, wedge
rate, and peak system RAM + disk IOPS/util (the sampler). Writes one JSON result.

cores are pinned with taskset (both arms share cpus 0..C-1) so the sweep is fair. Optional
--mem-gb confines the arm to a cgroup-v2 memory budget to demonstrate literal OOM collapse.
"""
import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys
import threading
import time
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
sys.path.insert(0, str(HERE))
from sampler import Sampler  # noqa: E402

GRADLE_HOME = os.environ.get("ARXAEL_GRADLE_HOME", "/opt/gradle/gradle-8.10.2")
LAUNCHER = REPO / "core/build/install/core/bin/core"
DOCKER = ["sudo", "docker"]
IMAGE = "arxael-bench:8.10.2-jdk21"
SLICE = "arxbench.slice"  # systemd slice for the modeled-box memory budget (cgroup v2)


def cap_slice(cores, mem_gb=0, io_iops=0, disk_dev="nvme0n1"):
    """Cap the slice for the modeled box. Containers join via --cgroup-parent, so these bound their
    AGGREGATE: memory.max -> OOM collapse; io.max (read+write IOPS) -> disk-bound collapse. Applied
    after the first container activates the slice. Only the requested limits are set."""
    props = [f"AllowedCPUs=0-{cores - 1}"]
    if mem_gb:
        props += [f"MemoryMax={mem_gb}G", "MemorySwapMax=0"]
    if io_iops:
        dev = f"/dev/{disk_dev}"
        props += [f"IOReadIOPSMax={dev} {io_iops}", f"IOWriteIOPSMax={dev} {io_iops}"]
    sh(["sudo", "systemctl", "set-property", "--runtime", SLICE] + props)


def slice_teardown():
    sh(["sudo", "systemctl", "stop", SLICE])

# Workload profiles — the heavy tasks agents actually run, so density numbers are realistic.
# NOTE: --rerun-tasks is NO LONGER baked in here — it's controlled by --cache-mode (below), which
# decouples "do real work each task" from "store to the build cache each task".
PROFILES = {
    "test":     {"tasks": ["test"], "args": ["--quiet"]},
    "coverage": {"tasks": ["test", "jacocoTestReport"], "args": ["--quiet"]},
    "mutation": {"tasks": ["pitest"], "args": ["--quiet"]},
}

# Cache/rerun mode. Two orthogonal knobs: rerun (do real work each task, an allowlisted CLI arg) and
# build_cache (a SUBSTRATE decision — the allowlist forbids cache flags as agent args, so the warm arm
# toggles it via ARXAEL_BUILD_CACHE; the container arm passes the CLI flag directly). The shared
# GRADLE_USER_HOME locks (fileHashes / journal-1 / modules-2) are exercised in ALL modes; build_cache
# only adds the per-worktree build-cache STORE traffic on top — so realistic vs stress ISOLATES how
# much of the contention is build-cache-store vs shared-home.
CACHE_MODES = {
    # realistic (headline): genuine recompile+retest each task, build cache OFF (no store hammering).
    "realistic":   {"args": ["--rerun-tasks"], "build_cache": False},
    # stress (labeled worst case): forced re-execute AND re-store every task — the old confounded combo.
    "stress":      {"args": ["--rerun-tasks"], "build_cache": True},
    # incremental: no forced rerun; build cache gives read hits / up-to-date (fleet-rebuild steady state).
    "incremental": {"args": [],                "build_cache": True},
}


# ----------------------------------------------------------------------------- helpers
def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def pctile(xs, p):
    if not xs:
        return 0.0
    s = sorted(xs)
    k = min(len(s) - 1, int(round((p / 100.0) * (len(s) - 1))))
    return round(s[k], 3)


def make_worktrees(base, fixture, n):
    base = pathlib.Path(base)
    if base.exists():
        shutil.rmtree(base)
    base.mkdir(parents=True)
    wts = []
    for i in range(n):
        wt = base / f"wt{i}"
        shutil.copytree(fixture, wt)
        wts.append(str(wt))
    return wts


class Agent(threading.Thread):
    """Closed-loop worker: run task() back-to-back until the deadline; record each result."""

    def __init__(self, task_fn, deadline):
        super().__init__(daemon=True)
        self.task_fn = task_fn
        self.deadline = deadline
        self.records = []  # (end_monotonic, latency_s, ok)

    def run(self):
        while time.monotonic() < self.deadline:
            t0 = time.monotonic()
            ok = self.task_fn()
            t1 = time.monotonic()
            self.records.append((t1, t1 - t0, ok))


def summarize(agents, warmup_end, window_end, sampler, meta):
    recs = [r for a in agents for r in a.records if warmup_end <= r[0] <= window_end]
    lat = [r[1] for r in recs]
    ok = sum(1 for r in recs if r[2])
    wedged = sum(1 for r in recs if not r[2])
    window_s = window_end - warmup_end
    completed = len(recs)
    # Tasks in flight at window close (started before, finished after): r=(end, latency, ok) -> start=end-latency.
    # Distinguishes a THROUGHPUT STALL (builds running, none finishing — goodput 0 but box pinned)
    # from genuine idle. Without this, an overloaded cell looks identical to a dead one.
    inflight_at_close = sum(1 for a in agents for r in a.records if (r[0] - r[1]) < window_end <= r[0])
    out = dict(meta)
    out.update({
        "completed": completed,
        "ok": ok,
        "wedged": wedged,
        "wedge_rate": round(wedged / completed, 4) if completed else 0.0,
        "goodput_per_min": round(ok / window_s * 60, 1) if window_s else 0.0,
        "p50_latency_s": pctile(lat, 50),
        "p95_latency_s": pctile(lat, 95),
        "p99_latency_s": pctile(lat, 99),
        "window_s": round(window_s, 1),
        "inflight_at_close": inflight_at_close,
        "stalled": completed == 0 and inflight_at_close > 0,
    })
    out.update(sampler.summary())
    return out


# ----------------------------------------------------------------------------- warm arm
def run_warm(args, taskset_prefix):
    state = pathlib.Path(args.state) / "warm"
    if state.exists():
        shutil.rmtree(state)
    state.mkdir(parents=True)
    wts = make_worktrees(state / "worktrees-src", args.fixture, args.agents)

    env = dict(os.environ)
    env.update({
        "ARXAEL_PORT": str(args.port),
        "ARXAEL_GRADLE_HOME": GRADLE_HOME,
        "ARXAEL_STATE_DIR": str(state / "daemon"),
        "ARXAEL_CORES": str(args.cores),
        # Design default ~cores (NOT max(agents,cores)). At agents>cores this LRU-evicts idle servers —
        # the real product behavior (daemon count tracks the bound, not the agent count). The old
        # max(agents,cores) pinned 32 connections at c16, inflating shared-home contention (de-confound).
        "ARXAEL_WARM_SERVERS": str(args.warm_servers if args.warm_servers else args.cores),
        "ARXAEL_HEAP_PER_SERVER_MB": str(args.heap_mb),
        # Generous overload timeout so legitimate queueing behind tens-of-seconds builds isn't misread
        # as OVERLOADED — we want goodput/latency, not a 60s fail-closed artifact (regime Phase 0).
        "ARXAEL_ACQUIRE_TIMEOUT_MS": str(int(args.acquire_timeout_ms)),
        # Build cache toggled at the substrate (allowlist forbids cache flags as agent args).
        "ARXAEL_BUILD_CACHE": "true" if CACHE_MODES[args.cache_mode]["build_cache"] else "false",
        "ARXAEL_PER_WORKTREE_HOME": "true" if args.per_worktree_home else "false",
        # PER-BUILD PARALLELISM (the fix): each build must parallelize to load the box. Default each
        # build to --max-workers=cores so ONE build can saturate, then concurrency oversubscribes.
        "ARXAEL_BUILD_WORKERS": str(args.build_workers if args.build_workers else args.cores),
        # Parallel test forks inside a build (fixture reads BENCH_TEST_FORKS for maxParallelForks);
        # inherited by the Tooling-API-spawned gradle daemons. Default 2: with --max-workers=cores the
        # multi-module test tasks already parallelize across modules, so a single build pins CPU (~92%)
        # at forks=2 while still completing; forks=cores over-subscribes (24 tasks × N forks) and thrashes.
        # Tuned UP per-phase (e.g. memory phase wants more JVMs).
        "BENCH_TEST_FORKS": str(args.test_forks if args.test_forks else 2),
    })
    # Shared read-only dependency cache: gradle reads GRADLE_RO_DEP_CACHE from the env, and the
    # Tooling-API-spawned daemons inherit it from this (the arxael) process. So per-worktree homes
    # resolve deps from one shared RO cache (no re-download, no write lock) — best of both.
    if args.ro_dep_cache:
        env["GRADLE_RO_DEP_CACHE"] = args.ro_dep_cache
    if args.mem_gb:
        # Capped run: the substrate's OWN memBound bounds concurrency to fit the budget — this IS
        # the product feature under test. No OS cap needed: the executor self-limits and we MEASURE
        # that peak RAM stays under the budget (if it doesn't, that's a real D7 finding).
        env["ARXAEL_USABLE_RAM_MB"] = str(int(args.mem_gb * 1024))
        env["ARXAEL_PER_BUILD_MB"] = str(args.per_build_mb)
    else:
        # Uncapped run: bound = cores * agentsPerCore. Default agentsPerCore=1 (the core bound);
        # the tuning sweep raises it to find the box's real ceiling (brief: "NOT fixed 1").
        apc = getattr(args, "agents_per_core", 0) or 0
        bound = max(1, round(args.cores * apc)) if apc > 0 else args.cores
        env["ARXAEL_MAX_CONCURRENT"] = str(bound)
    launch = taskset_prefix + [str(LAUNCHER)]  # cpu-pinned to 0..C-1 either way

    daemon = subprocess.Popen(launch, env=env,
                              stdout=open(state / "daemon.log", "w"), stderr=subprocess.STDOUT)
    base = f"http://127.0.0.1:{args.port}"
    try:
        _wait_health(base, daemon)

        prof = PROFILES[args.profile]
        gargs = prof["args"] + CACHE_MODES[args.cache_mode]["args"]  # build-cache is via ARXAEL_BUILD_CACHE
        def task_for(wt):
            body = json.dumps({"adapter": "gradle", "worktree": wt,
                               "tasks": prof["tasks"], "args": gargs,
                               "agentId": "bench"}).encode()
            def task():
                try:
                    req = urllib.request.Request(base + "/invoke", data=body,
                                                 headers={"Content-Type": "application/json"})
                    with urllib.request.urlopen(req, timeout=args.task_timeout) as r:
                        o = json.loads(r.read())
                    return o.get("status") == "SUCCESS"
                except Exception:
                    return False
            return task

        return _drive(args, [task_for(wt) for wt in wts])
    finally:
        try:
            urllib.request.urlopen(urllib.request.Request(base + "/shutdown", data=b"{}"), timeout=5)
        except Exception:
            pass
        time.sleep(0.5)
        daemon.terminate()
        try:
            daemon.wait(timeout=10)
        except Exception:
            daemon.kill()


def _wait_health(base, proc):
    for _ in range(150):
        try:
            with urllib.request.urlopen(base + "/health", timeout=2) as r:
                if json.loads(r.read()).get("ok"):
                    return
        except Exception:
            if proc.poll() is not None:
                raise RuntimeError("daemon exited during startup")
            time.sleep(0.2)
    raise RuntimeError("daemon never became healthy")


# ----------------------------------------------------------------------------- container arm
def ensure_image(args):
    if sh(DOCKER + ["image", "inspect", IMAGE]).returncode == 0:
        return
    ctx = pathlib.Path(args.state) / "img-ctx"
    if ctx.exists():
        shutil.rmtree(ctx)
    ctx.mkdir(parents=True)
    shutil.copytree(args.fixture, ctx / "fixture")
    (ctx / "Dockerfile").write_text(
        "FROM gradle:8.10.2-jdk21\n"
        "USER root\n"
        "COPY fixture /work\n"
        "WORKDIR /work\n"
        # Pre-cache deps into the image (fair: containers don't pay the JUnit download per build).
        "RUN gradle test --no-daemon -q || true\n"
    )
    r = sh(DOCKER + ["build", "-t", IMAGE, str(ctx)])
    if r.returncode != 0:
        raise RuntimeError("image build failed:\n" + r.stdout + r.stderr)


def run_container(args, cpuset):
    ensure_image(args)
    prof = PROFILES[args.profile]
    mode = CACHE_MODES[args.cache_mode]
    cache_flag = ["--build-cache"] if mode["build_cache"] else ["--no-build-cache"]
    bw = args.build_workers or args.cores
    par = ["--parallel", f"--max-workers={bw}"]  # match warm: each container build parallelizes too
    gradle_cmd = "cd /work && gradle " + " ".join(prof["tasks"] + prof["args"] + mode["args"] + cache_flag + par)
    io_iops = getattr(args, "io_iops", 0) or 0
    use_slice = bool(args.mem_gb or io_iops)
    cgroup_args = [f"--cgroup-parent={SLICE}"] if use_slice else []

    if args.churn:
        # Churn: a FRESH container (cold gradle daemon) per task; --rm auto-removes the container
        # AND its anonymous volume. Models agent-sandbox churn paying the cold-start tax the warm
        # shared executor avoids. (Run uncapped — the point is the tax, not OOM.)
        def churn_task(_i):
            cmd = DOCKER + ["run", "--rm", "--cpuset-cpus", cpuset] + cgroup_args + \
                  ["--entrypoint", "sh", IMAGE, "-c", gradle_cmd]
            def task():
                try:
                    return subprocess.run(cmd, capture_output=True, timeout=args.task_timeout).returncode == 0
                except Exception:
                    return False
            return task
        return _drive(args, [churn_task(i) for i in range(args.agents)])

    # Persistent-container (warm-within-life) path. NOTE rm -fv: the gradle base image declares a
    # cache VOLUME, so plain `rm -f` leaks a ~150MB anonymous volume per container (filled the disk
    # once already). -fv removes the volume with the container.
    names = [f"arxbench_{args.port}_{i}" for i in range(args.agents)]
    for n in names:
        sh(DOCKER + ["rm", "-fv", n])

    # Capped run: all containers join ONE slice capped at the modeled box budget, so their
    # AGGREGATE memory is bounded -> the kernel OOM-kills builds once the fleet outgrows the box
    # (the collapse). Uncapped run: no memory limit (the RAM curve still diverges from warm).
    try:
        for idx, n in enumerate(names):
            run = DOCKER + ["run", "-d", "--name", n, "--cpuset-cpus", cpuset] + cgroup_args + \
                  ["--entrypoint", "sleep", IMAGE, "infinity"]
            r = sh(run)
            if r.returncode != 0:
                raise RuntimeError(f"container start failed: {r.stderr}")
            if use_slice and idx == 0:
                # slice now active; apply the aggregate budget (memory and/or IOPS)
                cap_slice(args.cores, mem_gb=args.mem_gb, io_iops=io_iops, disk_dev=args.disk_dev)

        def task_for(name):
            cmd = DOCKER + ["exec", name, "sh", "-c", gradle_cmd]
            def task():
                try:
                    return subprocess.run(cmd, capture_output=True, timeout=args.task_timeout).returncode == 0
                except Exception:
                    return False
            return task

        return _drive(args, [task_for(n) for n in names])
    finally:
        for n in names:
            sh(DOCKER + ["rm", "-fv", n])
        if use_slice:
            slice_teardown()


class Recorders:
    """Best-effort deep recorders (atop full-system replay + pidstat per-process) for a run.

    No dark spots: atop captures EVERY process's cpu/mem/disk/net for after-the-fact replay
    (`atop -r <file>`), pidstat gives a flat per-process timeline. Both are best-effort — if the
    tool is missing the run still proceeds with the /proc sampler.
    """
    def __init__(self, run_dir):
        self.run_dir = run_dir
        self.procs = []

    def start(self):
        if not self.run_dir:
            return
        self._spawn(["atop", "-w", str(self.run_dir / "atop.raw"), "1"])
        self._spawn(["pidstat", "-h", "-r", "-u", "-d", "1"], out=self.run_dir / "pidstat.log")

    def _spawn(self, cmd, out=None):
        try:
            f = open(out, "w") if out else subprocess.DEVNULL
            self.procs.append(subprocess.Popen(cmd, stdout=f, stderr=subprocess.DEVNULL))
        except FileNotFoundError:
            pass  # tool not installed; sampler still covers the essentials

    def stop(self):
        for p in self.procs:
            p.terminate()
            try:
                p.wait(timeout=5)
            except Exception:
                p.kill()


def _open_loop(task_fns, deadline, rate, max_inflight_pool):
    """Open-loop load: tasks ARRIVE at a Poisson rate (independent of completion), unlike the
    closed-loop back-to-back model. This is what exercises the OVERLOADED/503 path and reveals
    queue-wait — a fixed arrival rate above capacity makes the warm executor shed gracefully
    (503) while the container arm just piles up. Records (end, latency, ok); `sheds` counts
    arrivals dropped client-side when in-flight exceeds a sane cap (backpressure)."""
    import random
    recs, lock = [], threading.Lock()
    inflight = threading.Semaphore(max(8, max_inflight_pool * 4))
    threads, sheds, i, n = [], 0, 0, len(task_fns)

    def worker(fn):
        try:
            t0 = time.monotonic(); ok = fn(); t1 = time.monotonic()
            with lock:
                recs.append((t1, t1 - t0, ok))
        finally:
            inflight.release()

    while time.monotonic() < deadline:
        fn = task_fns[i % n]; i += 1
        if inflight.acquire(blocking=False):
            t = threading.Thread(target=worker, args=(fn,), daemon=True)
            t.start(); threads.append(t)
        else:
            sheds += 1  # client can't keep up — count as shed, don't pile threads unbounded
        time.sleep(random.expovariate(rate))

    for t in threads:
        t.join(timeout=max(5.0, deadline - time.monotonic() + 30))

    class _A:
        pass
    a = _A(); a.records = recs
    return [a], sheds


# ----------------------------------------------------------------------------- common driver
def _drive(args, task_fns):
    run_dir = pathlib.Path(args.run_dir) if args.run_dir else None
    if run_dir:
        run_dir.mkdir(parents=True, exist_ok=True)
    ts_path = str(run_dir / "timeseries.jsonl") if run_dir else None

    # Pre-warm: run each worker's task once (concurrently) and discard, so the timed window
    # measures STEADY-STATE density, not one-off cold gradle-daemon start + dependency download.
    # Fair across arms — the container arm's per-container daemon is warmed the same way.
    if args.prewarm:
        warmers = [threading.Thread(target=fn, daemon=True) for fn in task_fns]
        for w in warmers:
            w.start()
        for w in warmers:
            w.join()

    recorders = Recorders(run_dir)
    recorders.start()
    sampler = Sampler(disk_dev=args.disk_dev, interval=1.0, timeseries_path=ts_path)
    sampler.start()
    start = time.monotonic()
    deadline = start + args.warmup + args.window
    if args.rate and args.rate > 0:
        agents, sheds = _open_loop(task_fns, deadline, args.rate, args.agents)
    else:
        agents = [Agent(fn, deadline) for fn in task_fns]
        for a in agents:
            a.start()
        for a in agents:
            a.join()
        sheds = 0
    sampler.stop()
    recorders.stop()

    result = summarize(agents, start + args.warmup, deadline, sampler, meta={
        "arm": args.arm, "agents": args.agents, "cores": args.cores, "profile": args.profile,
        "cache_mode": args.cache_mode, "warm_servers": args.warm_servers or args.cores,
        "build_workers": args.build_workers or args.cores, "test_forks": args.test_forks or 2,
        "per_worktree_home": bool(args.per_worktree_home),
        "mem_gb": args.mem_gb or 0, "classes": args.classes,
        "agents_per_core": getattr(args, "agents_per_core", 0) or 0,
        "churn": bool(getattr(args, "churn", False)),
        "rate": args.rate or 0,
        "io_iops": getattr(args, "io_iops", 0) or 0,
        "client_sheds": sheds,
    })
    if run_dir:
        (run_dir / "result.json").write_text(json.dumps(result, indent=2))
        # Pull the daemon's own logs into the bundle (warm arm).
        for src in [pathlib.Path(args.state) / "warm" / "daemon.log",
                    pathlib.Path(args.state) / "warm" / "daemon" / "events.jsonl"]:
            if src.exists():
                shutil.copy(src, run_dir / src.name)
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--arm", choices=["warm", "container"], required=True)
    ap.add_argument("--agents", type=int, required=True)
    ap.add_argument("--cores", type=int, default=os.cpu_count())
    ap.add_argument("--profile", choices=list(PROFILES), default="test")
    ap.add_argument("--cache-mode", choices=list(CACHE_MODES), default="realistic", dest="cache_mode",
                    help="realistic=rerun+no-cache (headline); stress=rerun+store; incremental=cache-read")
    ap.add_argument("--warm-servers", type=int, default=0,
                    help="warm-server cap (0 = design default = cores; the bench no longer pins it to agents)")
    ap.add_argument("--acquire-timeout-ms", type=float, default=600000,
                    help="executor permit-acquire timeout (ms); generous so queueing != false OVERLOADED")
    ap.add_argument("--per-worktree-home", dest="per_worktree_home", action="store_true",
                    help="EXPERIMENTAL: per-worktree Gradle user home (removes the shared-home cache lock; limit-finding)")
    ap.add_argument("--ro-dep-cache", default="",
                    help="shared read-only dependency cache dir (GRADLE_RO_DEP_CACHE); pairs with --per-worktree-home "
                         "to remove the lock AND avoid re-resolving deps per home (a RO-shared + per-wt-writable)")
    ap.add_argument("--build-workers", type=int, default=0,
                    help="per-build gradle --max-workers (0 = cores; each build parallelizes to load the box)")
    ap.add_argument("--test-forks", type=int, default=0,
                    help="per-build test maxParallelForks via BENCH_TEST_FORKS (0 = cores)")
    ap.add_argument("--run-dir", default="")
    ap.add_argument("--mem-gb", type=float, default=0)
    ap.add_argument("--per-build-mb", type=int, default=1024)
    ap.add_argument("--agents-per-core", type=float, default=0,
                    help="uncapped warm: bound = cores*apc (0 = legacy 1x cores)")
    ap.add_argument("--churn", action="store_true",
                    help="container arm: fresh cold container per task (cold-start tax)")
    ap.add_argument("--rate", type=float, default=0,
                    help="open-loop Poisson arrival rate (tasks/s); 0 = closed-loop")
    ap.add_argument("--io-iops", type=int, default=0, dest="io_iops",
                    help="container arm: throttle slice read+write IOPS via cgroup io.max (0 = unthrottled)")
    ap.add_argument("--fixture", default="/tmp/bench-fixture")
    ap.add_argument("--classes", type=int, default=150)
    ap.add_argument("--state", default="/tmp/arxbench")
    ap.add_argument("--port", type=int, default=8731)
    ap.add_argument("--warmup", type=float, default=15)
    ap.add_argument("--window", type=float, default=60)
    ap.add_argument("--prewarm", dest="prewarm", action="store_true", default=True)
    ap.add_argument("--no-prewarm", dest="prewarm", action="store_false")
    ap.add_argument("--task-timeout", type=float, default=180)
    ap.add_argument("--heap-mb", type=int, default=768)
    ap.add_argument("--disk-dev", default="nvme0n1")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    cpuset = f"0-{args.cores - 1}" if args.cores > 1 else "0"
    taskset_prefix = ["taskset", "-c", cpuset]

    result = run_warm(args, taskset_prefix) if args.arm == "warm" else run_container(args, cpuset)

    line = json.dumps(result)
    print(line)
    if args.out:
        pathlib.Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        with open(args.out, "a") as f:
            f.write(line + "\n")


if __name__ == "__main__":
    main()