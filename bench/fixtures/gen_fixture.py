#!/usr/bin/env python3
"""Generate a scalable gradle compile+test fixture.

The benchmark's unit of work is one build of this project (compile N classes + run their
JUnit tests). Size scales with --classes so a single build lands in a measurable window,
and the SAME generated project is used by BOTH arms (shared-warm and container-per-agent)
so a "task" is identical work on each side — the comparison is fair.

Two shapes:
  --modules 1 (default)  single-module project at <dest> (legacy; byte-identical to before).
  --modules K (K>1)      a realistic MULTI-MODULE project: K subprojects (:mod0..:modK-1),
                         each with --classes classes + tests, chained by a real inter-module
                         dependency (modI implementation(project(modI-1)) and one class calls
                         into the previous module). This exercises Gradle's cross-module
                         configuration / ordered compile / per-module test-fork path — a
                         realistic build whose wall time (tens of seconds) makes cache-lock
                         HOLD time realistic vs fixed coordination overhead,
                         instead of a ~1.7s toy where coordination overhead dominates.

Deterministic: same args -> byte-identical tree (--fanin uses a fixed --seed, so still reproducible).
"""
import argparse
import pathlib
import random
import shutil


def pkg(midx):
    return "dev/arxael/bench" if midx is None else f"dev/arxael/bench/mod{midx}"


def pkg_dot(midx):
    return "dev.arxael.bench" if midx is None else f"dev.arxael.bench.mod{midx}"


def klass(i: int, methods: int, midx=None, dep_midxs=None, work=8) -> str:
    pk = pkg_dot(midx)
    dep_midxs = dep_midxs or []
    lines = [f"package {pk};", ""]
    # First class of a dependent module calls into EACH dependency module → real compile dependencies.
    # Use fully-qualified references (no imports) so a dep's C0 doesn't clash with the local C0.
    calls = i == 0 and len(dep_midxs) > 0
    lines.append(f"public final class C{i} {{")
    for m in range(methods):
        # A little real arithmetic so javac + JIT have something to chew on.
        lines.append(f"    public static long f{m}(long x) {{")
        lines.append(f"        long a = x + {i * 31 + m};")
        lines.append(f"        for (int k = 0; k < {work}; k++) a = (a * 1103515245L + 12345L) ^ (a >>> 7);")
        if calls and m == 0:
            # cross-module calls (fully-qualified): forces modI to compile after every dependency.
            for d in dep_midxs:
                lines.append(f"        a ^= {pkg_dot(d)}.C0.f0(a);")
        lines.append(f"        return a + mix(x);")
        lines.append("    }")
    lines.append("    private static long mix(long x) { return x ^ 0x9E3779B97F4A7C15L; }")
    lines.append("}")
    return "\n".join(lines) + "\n"


def test(i: int, methods: int, midx=None) -> str:
    lines = [
        f"package {pkg_dot(midx)};",
        "",
        "import org.junit.jupiter.api.Test;",
        "import static org.junit.jupiter.api.Assertions.assertNotEquals;",
        "",
        f"class C{i}Test {{",
    ]
    for m in range(methods):
        lines.append("    @Test")
        lines.append(f"    void t{m}() {{ assertNotEquals(0L, C{i}.f{m}({i + m + 1})); }}")
    lines.append("}")
    return "\n".join(lines) + "\n"


# Per-module (or single-module) build script. {DEPS} is the inter-module dependency line (or "").
BUILD = """\
// Generated benchmark fixture — real compile + JUnit5 test workload, with coverage (JaCoCo)
// and mutation (PIT) wired so the benchmark can measure the heavy tasks agents actually run.
plugins {
    java
    jacoco
    id("info.solidsoft.pitest") version "1.15.0"
}

repositories { mavenCentral() }

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
{DEPS}}

tasks.test {
    useJUnitPlatform()
    // Scaled by the substrate via --max-workers; forks share the core budget.
    maxParallelForks = (System.getenv("BENCH_TEST_FORKS")?.toIntOrNull()) ?: 1
}

pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(setOf("{PITGLOB}"))
    threads.set((System.getenv("BENCH_PIT_THREADS")?.toIntOrNull()) ?: 2)
    timestampedReports.set(false)
    outputFormats.set(setOf("XML"))
}
"""


def write_module(root: pathlib.Path, midx, classes, methods, dep_midxs, work=8):
    """Write one module's sources + build file. midx=None => single-module at root.
    dep_midxs: list of earlier module indices this module depends on (compile + gradle project deps)."""
    dep_midxs = dep_midxs or []
    sub = root if midx is None else (root / f"mod{midx}")
    main_dir = sub / "src/main/java" / pkg(midx)
    test_dir = sub / "src/test/java" / pkg(midx)
    main_dir.mkdir(parents=True)
    test_dir.mkdir(parents=True)
    for i in range(classes):
        (main_dir / f"C{i}.java").write_text(klass(i, methods, midx, dep_midxs, work))
        (test_dir / f"C{i}Test.java").write_text(test(i, methods, midx))
    deps = "".join(f'    implementation(project(":mod{d}"))\n' for d in dep_midxs)
    pitglob = "dev.arxael.bench.*" if midx is None else f"dev.arxael.bench.mod{midx}.*"
    (sub / "build.gradle.kts").write_text(BUILD.replace("{DEPS}", deps).replace("{PITGLOB}", pitglob))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dest")
    ap.add_argument("--classes", type=int, default=150, help="classes per module")
    ap.add_argument("--methods", type=int, default=6)
    ap.add_argument("--work", type=int, default=8, help="inner compute-loop iterations per method (test weight)")
    ap.add_argument("--modules", type=int, default=1, help="1 = single-module (legacy); >1 = multi-module chain")
    ap.add_argument("--independent", action="store_true",
                    help="multi-module with NO cross-module deps (each module isolated) — enables the merge "
                         "disjoint fast-path (a change to one module can't affect another's tests)")
    ap.add_argument("--fanin", type=int, default=0,
                    help="wide DAG: each module depends on up to N randomly-chosen EARLIER modules "
                         "(deterministic seed). 0 = use the default linear chain. Produces the realistic "
                         "middle (medium dependency closures) between --independent and the deep chain.")
    ap.add_argument("--seed", type=int, default=1234, help="seed for --fanin dep selection (deterministic)")
    args = ap.parse_args()

    dest = pathlib.Path(args.dest)
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)

    if args.modules <= 1:
        (dest / "settings.gradle.kts").write_text('rootProject.name = "bench-fixture"\n')
        write_module(dest, None, args.classes, args.methods, None, args.work)
        print(f"generated single-module: {args.classes} classes x {args.methods} methods (+tests) at {dest}")
        return

    includes = "\n".join(f'include(":mod{i}")' for i in range(args.modules))
    (dest / "settings.gradle.kts").write_text('rootProject.name = "bench-fixture"\n' + includes + "\n")
    # Root build stays empty: each module carries its own plugins/deps (so the test/coverage/mutation
    # task names resolve in every subproject and run across the whole build).
    (dest / "build.gradle.kts").write_text("// multi-module aggregate; modules carry their own config\n")

    # Per-module dependency lists for the three topologies:
    #   --independent : []            (no edges)         -> closure size 1 everywhere
    #   --fanin K     : K random earlier modules         -> realistic medium closures (the wide DAG)
    #   default       : [i-1]         (linear chain)     -> closures grow toward the leaves
    rng = random.Random(args.seed)
    deps_of = {}
    for i in range(args.modules):
        if args.independent:
            deps_of[i] = []
        elif args.fanin > 0:
            k = min(args.fanin, i)
            deps_of[i] = sorted(rng.sample(range(i), k)) if k > 0 else []
        else:
            deps_of[i] = [i - 1] if i > 0 else []

    for i in range(args.modules):
        write_module(dest, i, args.classes, args.methods, deps_of[i], args.work)
    total = args.modules * args.classes
    shape = "independent" if args.independent else (f"wide-DAG(fanin={args.fanin})" if args.fanin > 0 else "chained")
    edges = sum(len(v) for v in deps_of.values())
    print(f"generated multi-module: {args.modules} modules x {args.classes} classes "
          f"x {args.methods} methods = {total} classes (+tests, {shape}, {edges} dep-edges) at {dest}")


if __name__ == "__main__":
    main()
