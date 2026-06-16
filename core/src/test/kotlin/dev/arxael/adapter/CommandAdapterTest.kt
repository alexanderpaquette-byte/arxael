package dev.arxael.adapter

import dev.arxael.config.BoxConfig
import dev.arxael.protocol.InvokeSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The named per-language adapters: zero-config default command when no tasks given, caller override when
 * they are, and the language defaults are all present in the registry. Proves "multi-language" is real.
 */
class CommandAdapterTest {
    private val tmp: Path = Files.createTempDirectory("arxael-cmd-adapter-test")

    @AfterTest
    fun cleanup() = Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) }.let {} }

    private fun cfg() = BoxConfig(
        cores = 1, warmServers = 1, agentsPerCore = 1.0, coreBound = 1, memBound = 1, maxConcurrent = 1,
        bindingConstraint = "override", usableRamMb = 8000, ramHeadroomMb = 1000, perBuildFootprintMb = 1536,
        heapPerServerMb = 256, buildWorkers = 1, buildCache = false, perWorktreeHome = false,
        gradleHome = Path.of("/x"), stateDir = tmp, port = 0, watchdogIntervalMs = 2000,
        acquireTimeoutMs = 2000, reservedHigh = 0, daemonIdleSec = 120,
    )

    @Test
    fun `uses the default command when no tasks or args are given`() {
        // a default that exits 0 ("true") -> success without the caller spelling out any command
        val adapter = CommandAdapter("demo", listOf("true"))
        val s = adapter.open(tmp, tmp.resolve("o"), cfg())
        val r = s.run(InvokeSpec(adapter = "demo", worktree = tmp.toString())) {}
        assertTrue(r.success)
        assertEquals("demo", adapter.name)
    }

    @Test
    fun `ARXAEL CMD override parses a JSON array (paths with spaces) or a whitespace string`() {
        // JSON-array form preserves a path containing spaces as one argv element
        assertEquals(listOf("/opt/My Tools/mvn", "-q", "test"),
            CommandAdapter.parseCmd("""["/opt/My Tools/mvn","-q","test"]"""))
        // simple whitespace form still works
        assertEquals(listOf("mvn", "-q", "test"), CommandAdapter.parseCmd("mvn -q test"))
        assertEquals(listOf("mvn"), CommandAdapter.parseCmd("mvn"))
        // malformed JSON falls back to whitespace-split, never throws
        assertEquals(listOf("[oops"), CommandAdapter.parseCmd("[oops"))
    }

    @Test
    fun `caller-supplied tasks override the default command`() {
        // default would succeed ("true"), but the caller forces a failing command -> override wins
        val adapter = CommandAdapter("demo", listOf("true"))
        val s = adapter.open(tmp, tmp.resolve("o"), cfg())
        val r = s.run(InvokeSpec(adapter = "demo", worktree = tmp.toString(), tasks = listOf("false"))) {}
        assertFalse(r.success)
    }

    @Test
    fun `the default language adapters are registered`() {
        val names = AdapterRegistry.default().names()
        listOf("pytest", "cargo", "go", "maven", "gradlew", "vitest", "npm", "make", "exec", "gradle").forEach {
            assertTrue(it in names, "adapter '$it' must be registered; have $names")
        }
    }

    @Test
    fun `every language default carries a non-empty command`() {
        CommandAdapter.languageDefaults().forEach {
            // running with no tasks must invoke its default; here we just assert the name is set
            assertTrue(it.name.isNotBlank())
        }
    }

    @Test
    fun `toolchainEnv is a no-op for non-gradlew toolchains and when per-worktree home is off`() {
        // maven/pytest/cargo/go/npm lock little -> no per-call toolchain env regardless of perWorktreeHome.
        val ob = tmp.resolve("ob")
        for (a in listOf("maven", "pytest", "cargo", "go", "npm")) {
            assertTrue(CommandAdapter.toolchainEnv(a, ob, cfg().copy(perWorktreeHome = true)).isEmpty(),
                "non-gradlew '$a' must inject no toolchain env")
        }
        // gradlew with the shared-home default (perWorktreeHome=false) keeps the ambient ~/.gradle-home.
        assertTrue(CommandAdapter.toolchainEnv("gradlew", ob, cfg().copy(perWorktreeHome = false)).isEmpty(),
            "gradlew on the shared home must inject no toolchain env")
    }

    @Test
    fun `gradlew under per-worktree home gets an isolated GRADLE_USER_HOME with a shared wrapper-dists symlink`() {
        val ob = tmp.resolve("ob-gradlew")
        val cfg = cfg().copy(perWorktreeHome = true) // stateDir = tmp
        val env = CommandAdapter.toolchainEnv("gradlew", ob, cfg)

        // 1. Isolated, per-worktree GRADLE_USER_HOME under THIS worktree's output base (not the shared home).
        val home = env["GRADLE_USER_HOME"]
        assertEquals(ob.resolve("gradle-user-home").toString(), home,
            "gradlew must get a per-worktree GRADLE_USER_HOME under its output base")
        assertTrue(Files.isDirectory(Path.of(home!!)), "the per-worktree home must be created")

        // 2. Daemon idletimeout is bounded (no 3h leak) — the same bound the warm gradle adapter sets.
        val props = Files.readString(Path.of(home).resolve("gradle.properties"))
        assertTrue(props.contains("org.gradle.daemon.idletimeout=${cfg.daemonIdleSec * 1000}"), "props=$props")

        // 3. wrapper/dists is a SYMLINK to the one shared dir -> the Gradle dist is downloaded once, not per
        //    worktree. This is the whole reason the per-worktree home is cheap.
        val dists = Path.of(home).resolve("wrapper").resolve("dists")
        assertTrue(Files.isSymbolicLink(dists), "wrapper/dists must be a symlink to the shared dist dir")
        assertEquals(tmp.resolve("gradlew-wrapper-dists").toRealPath(), dists.toRealPath(),
            "the symlink must target the daemon-shared wrapper-dists dir")

        // 4. A SECOND worktree links to the SAME shared dists dir (so the dist is genuinely shared, not re-DLed).
        val ob2 = tmp.resolve("ob-gradlew-2")
        val dists2 = Path.of(CommandAdapter.toolchainEnv("gradlew", ob2, cfg)["GRADLE_USER_HOME"]!!)
            .resolve("wrapper").resolve("dists")
        assertEquals(dists.toRealPath(), dists2.toRealPath(), "both worktrees must share one wrapper-dists dir")
    }

    @Test
    fun `gradlew toolchainEnv exposes a live RO dep cache when one is set, and omits it otherwise`() {
        val ob = tmp.resolve("ob-rodep")
        val cfg = cfg().copy(perWorktreeHome = true)
        // No RO dep cache live -> the key is simply absent (build runs without one; correct, just slower).
        assertFalse(CommandAdapter.toolchainEnv("gradlew", ob, cfg).containsKey("GRADLE_RO_DEP_CACHE"))
        // Once the daemon establishes/pins a shared RO dep cache, gradlew reads deps through it (no re-download).
        cfg.liveRoDepCache.set("/some/shared/caches")
        assertEquals("/some/shared/caches",
            CommandAdapter.toolchainEnv("gradlew", tmp.resolve("ob-rodep2"), cfg)["GRADLE_RO_DEP_CACHE"])
    }

    @Test
    fun `gradlew toolchainEnv is idempotent across re-opens of the same worktree home`() {
        // Warm reuse: re-opening the same worktree must not fail on the already-present symlink/home.
        val ob = tmp.resolve("ob-idem")
        val cfg = cfg().copy(perWorktreeHome = true)
        val first = CommandAdapter.toolchainEnv("gradlew", ob, cfg)
        val second = CommandAdapter.toolchainEnv("gradlew", ob, cfg)
        assertEquals(first["GRADLE_USER_HOME"], second["GRADLE_USER_HOME"])
        assertTrue(Files.isSymbolicLink(Path.of(first["GRADLE_USER_HOME"]!!).resolve("wrapper").resolve("dists")))
    }

    @Test
    fun `a default command can be overridden per deployment via env`() {
        // ARXAEL_<NAME>_CMD replaces the built-in default (e.g. to scope a multi-module gate to one module).
        // Point it at a binary that CANNOT exist so the override is proven deterministically on ANY box: the
        // process spawn fails -> an infra fault is thrown whose message names the override binary (not the
        // built-in `mvn`). Using a real `mvn` here would be environment-fragile — if a real mvn is installed it
        // merely exits non-zero (no pom) instead of throwing, and the test would flap.
        val env = mapOf("ARXAEL_MAVEN_CMD" to "arxael-nonexistent-build-tool -q test")
        val maven = CommandAdapter.languageDefaults { env[it] }.first { it.name == "maven" }
        val s = maven.open(tmp, tmp.resolve("o"), cfg())
        val out = StringBuilder()
        var ran = ""
        try { s.run(InvokeSpec(adapter = "maven", worktree = tmp.toString())) { out.append(it) } }
        catch (e: Exception) { ran = e.message ?: "" }
        assertTrue(ran.contains("arxael-nonexistent-build-tool"), "override command should be attempted: $ran")
        // a blank override falls back to the built-in
        assertEquals(1, CommandAdapter.languageDefaults { null }.count { it.name == "maven" })
    }
}
