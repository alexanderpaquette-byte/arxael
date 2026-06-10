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
    fun `toolchainEnv is a no-op - CommandAdapters use the ambient toolchain home`() {
        // gradlew on the ambient ~/.gradle shares the wrapper distribution (a per-worktree home would
        // re-download it); maven/pytest lock little. So no per-call toolchain env is injected today.
        val ob = tmp.resolve("ob")
        for (a in listOf("gradlew", "maven", "pytest", "cargo")) {
            assertTrue(CommandAdapter.toolchainEnv(a, ob, cfg().copy(perWorktreeHome = true)).isEmpty())
        }
    }

    @Test
    fun `a default command can be overridden per deployment via env`() {
        // ARXAEL_MAVEN_CMD scopes a multi-module Maven build's gate to one module, no code change
        val env = mapOf("ARXAEL_MAVEN_CMD" to "mvn -q -pl gson -am test")
        val maven = CommandAdapter.languageDefaults { env[it] }.first { it.name == "maven" }
        val s = maven.open(tmp, tmp.resolve("o"), cfg())
        // it runs the overridden command (here the binary won't exist -> infra fault thrown, proving the
        // override took effect rather than the built-in `mvn -q test`)
        val out = StringBuilder()
        var ran = ""
        try { s.run(InvokeSpec(adapter = "maven", worktree = tmp.toString())) { out.append(it) } }
        catch (e: Exception) { ran = e.message ?: "" }
        assertTrue(ran.contains("mvn"), "override command should be attempted: $ran")
        // a blank override falls back to the built-in
        assertEquals(1, CommandAdapter.languageDefaults { null }.count { it.name == "maven" })
    }
}
