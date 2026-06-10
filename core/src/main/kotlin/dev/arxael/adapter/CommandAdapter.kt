package dev.arxael.adapter

import dev.arxael.config.BoxConfig
import dev.arxael.protocol.InvokeSpec
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * A named, language-specific adapter built on [ProcExec]. This is what makes "multi-language" real and
 * ergonomic: an agent says `{"adapter":"pytest","worktree":"..."}` with NO tasks and gets the conventional
 * test command for that ecosystem; or it overrides with explicit `tasks`/`args` (e.g. a single test file).
 *
 * Unlike the fully-generic [ExecAdapter] (where the caller must spell out the whole command), each
 * [CommandAdapter] carries a sensible [defaultCommand] so the common case is zero-config. There is no warm
 * daemon — these toolchains are process-per-invocation — so the density win is the bounded executor + the
 * merge workflow, identical to [ExecAdapter]. All of [GradleAdapter][dev.arxael.adapter.gradle.GradleAdapter]'s
 * resource tuning (footprint, workers) is JVM-calibrated; the governor learns a new toolchain's footprint at
 * runtime (EWMA), so the bound still self-corrects for these (documented in docs/LIMITATIONS.md #3).
 */
class CommandAdapter(
    override val name: String,
    private val defaultCommand: List<String>,
) : BuildAdapter {
    override fun open(worktree: Path, outputBase: Path, config: BoxConfig): AdapterSession =
        Session(worktree, config.acquireTimeoutCapMs, defaultCommand, toolchainEnv(name, outputBase, config))

    private class Session(
        private val worktree: Path,
        private val timeoutMs: Long,
        private val defaultCommand: List<String>,
        private val env: Map<String, String>,
    ) : AdapterSession {
        override fun run(spec: InvokeSpec, sink: (String) -> Unit): RunResult {
            // Caller-supplied command wins; otherwise the ecosystem default (the zero-config path).
            val cmd = if (spec.tasks.isEmpty() && spec.args.isEmpty()) defaultCommand else spec.tasks + spec.args
            return ProcExec.run(worktree, cmd, timeoutMs, env, sink)
        }

        override fun healthy() = true
        override fun close() {}
    }

    companion object {
        /**
         * The built-in language adapters. Each maps an ecosystem name to its conventional test command. A new
         * ecosystem is one line here (or the operator can wire a custom [CommandAdapter]); the executor, merge
         * orchestration, and /invoke surface are unchanged — the SPI is the only contract.
         *
         * Each default command is overridable per deployment via `ARXAEL_<NAME>_CMD`, so a real project can
         * scope its gate properly without code changes — e.g. `ARXAEL_MAVEN_CMD="mvn -q -pl core -am test"`.
         * Two forms: a simple space-separated string, OR a JSON array `["/opt/My Tools/mvn","-q","test"]` when
         * a path/arg contains spaces (the space-split form would mangle it into a broken argv).
         */
        fun languageDefaults(getenv: (String) -> String? = { System.getenv(it) }): List<CommandAdapter> = listOf(
            cmd("pytest", listOf("pytest", "-q"), getenv),         // Python
            cmd("cargo", listOf("cargo", "test"), getenv),         // Rust
            cmd("go", listOf("go", "test", "./..."), getenv),      // Go
            cmd("maven", listOf("mvn", "-q", "test"), getenv),     // JVM (Maven — the other big JVM ecosystem)
            cmd("gradlew", listOf("./gradlew", "test"), getenv),   // JVM (a project's OWN Gradle wrapper — its pinned version)
            cmd("vitest", listOf("npx", "--yes", "vitest", "run"), getenv), // JS/TS (Vitest)
            cmd("npm", listOf("npm", "test"), getenv),             // JS/TS (package.json test script)
            cmd("make", listOf("make", "test"), getenv),           // anything with a Makefile target
        )

        /** Build an adapter whose default command is the env override `ARXAEL_<NAME>_CMD` if set, else [fallback]. */
        internal fun cmd(name: String, fallback: List<String>, getenv: (String) -> String?): CommandAdapter {
            val override = getenv("ARXAEL_${name.uppercase()}_CMD")?.trim()?.takeIf { it.isNotEmpty() }
            return CommandAdapter(name, override?.let { parseCmd(it) } ?: fallback)
        }

        /** Parse an `ARXAEL_<NAME>_CMD` override: a JSON array `["/path with spaces/bin","arg"]` for an exact
         *  argv (handles spaces), else whitespace-split for the simple `mvn -q test` form. Never throws —
         *  malformed JSON falls back to the split. Argv is run directly (ProcessBuilder, no shell), so a token
         *  containing shell metacharacters is an inert literal arg, not an injection vector. */
        internal fun parseCmd(s: String): List<String> =
            runCatching { Json.decodeFromString<List<String>>(s) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: s.split(Regex("\\s+"))

        /**
         * Per-worktree toolchain-home env for a CommandAdapter. Currently a NO-OP (returns empty) — kept as
         * the one place to add per-toolchain isolation if a real workload shows the ambient home binds.
         *
         * Why NOT a per-worktree `GRADLE_USER_HOME` for `gradlew` (an earlier attempt, reverted): the Gradle
         * **distribution** the wrapper downloads lives in `GRADLE_USER_HOME/wrapper/dists`, and
         * `GRADLE_RO_DEP_CACHE` shares only `modules-2` deps — NOT the distribution. So a per-worktree home
         * makes every worktree re-download the wrapper's Gradle (hundreds of MB, minutes each) — far worse
         * than the `~/.gradle` lock it would avoid. And the merge gate runs `gradlew` **sequentially**
         * (non-gradle PRs route batched), so there's no concurrent-lock problem to solve there anyway.
         * A correct future version would per-worktree the caches but SHARE `wrapper/dists` (symlink). The
         * ProcExec env hook stays (general + harmless) for when that's worth building.
         */
        @Suppress("UNUSED_PARAMETER") // params are the extension point for a future per-toolchain isolation
        internal fun toolchainEnv(name: String, outputBase: Path, config: BoxConfig): Map<String, String> =
            emptyMap()
    }
}
