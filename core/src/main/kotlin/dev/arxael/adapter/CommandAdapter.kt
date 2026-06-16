package dev.arxael.adapter

import dev.arxael.config.BoxConfig
import dev.arxael.protocol.InvokeSpec
import kotlinx.serialization.json.Json
import java.nio.file.Files
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
         * Per-worktree toolchain-home env for a CommandAdapter. For `gradlew` under [BoxConfig.perWorktreeHome]
         * this returns a per-worktree `GRADLE_USER_HOME` (and a `GRADLE_RO_DEP_CACHE` when one is live), so the
         * merge-gate's `./gradlew` no longer contends on the shared `~/.gradle-home` cross-process cache locks
         * (`journal-1`/`fileHashes`/`javaCompile`) — the same lock ceiling the warm `gradle` adapter already
         * dodges via its per-worktree home (GradleAdapter.open). Every other toolchain returns empty (maven/
         * pytest/cargo/go/npm lock little; no measured benefit).
         *
         * The reason this was a no-op before: a naive per-worktree `GRADLE_USER_HOME` makes the project's OWN
         * wrapper re-download the pinned Gradle **distribution** into `GRADLE_USER_HOME/wrapper/dists` (hundreds
         * of MB, minutes) for every worktree — `GRADLE_RO_DEP_CACHE` shares only `modules-2` deps, NOT the
         * distribution. The fix (the deferred plan) is to point each per-worktree home's `wrapper/dists` at ONE
         * shared directory via a symlink: the wrapper resolves the dist under `GRADLE_USER_HOME/wrapper/dists`
         * (Gradle wrapper docs), so the first worktree downloads it into the shared dir and every other reads it
         * back through the link — per-worktree writable caches (no lock) WITHOUT the dist re-download. Deps are
         * shared read-only through `GRADLE_RO_DEP_CACHE`, exactly as the warm gradle adapter does.
         *
         * Falls back to the ambient shared home (empty env) if the per-worktree home can't be prepared, so a
         * filesystem hiccup degrades to the old behaviour rather than failing the build.
         */
        internal fun toolchainEnv(name: String, outputBase: Path, config: BoxConfig): Map<String, String> {
            if (name != "gradlew" || !config.perWorktreeHome) return emptyMap()
            return runCatching {
                val userHome = outputBase.resolve("gradle-user-home")
                Files.createDirectories(userHome)
                // Bound the wrapper-spawned daemon's life the same way the warm gradle adapter does (Gradle's
                // 3h default would leak a GB-scale daemon per per-worktree home across a long uptime).
                Files.writeString(userHome.resolve("gradle.properties"),
                    "org.gradle.daemon.idletimeout=${config.daemonIdleSec * 1000}\n")
                linkSharedWrapperDists(userHome, config)
                val env = HashMap<String, String>()
                env["GRADLE_USER_HOME"] = userHome.toString()
                // Shared READ-ONLY dep cache (if the daemon established one): the per-worktree home reads deps
                // from it and writes only NEW deps into its own home -> no re-download, no shared write lock.
                config.liveRoDepCache.get()?.let { env["GRADLE_RO_DEP_CACHE"] = it }
                env
            }.getOrDefault(emptyMap())
        }

        /**
         * Point this per-worktree home's `wrapper/dists` at ONE shared directory so the wrapper's Gradle
         * distribution is downloaded once (by whichever worktree hits it first) and reused by every other
         * worktree — the symlink that makes a per-worktree `GRADLE_USER_HOME` cheap. The shared dir is plain
         * (writable) so the first download lands there; subsequent worktrees read it back through the link. The
         * wrapper's per-distribution `.lck` is brief (unzip-only, one Gradle version) — NOT the cross-process
         * build-cache locks the per-worktree home exists to eliminate. Best-effort: a pre-existing non-symlink
         * `wrapper/dists` (e.g. a partially-warmed home) is left as-is rather than clobbered.
         */
        private fun linkSharedWrapperDists(userHome: Path, config: BoxConfig) {
            val shared = config.stateDir.resolve("gradlew-wrapper-dists")
            Files.createDirectories(shared)
            val wrapperDir = userHome.resolve("wrapper")
            Files.createDirectories(wrapperDir)
            val dists = wrapperDir.resolve("dists")
            if (Files.isSymbolicLink(dists)) return            // already linked (warm reuse) — nothing to do
            if (Files.exists(dists)) return                     // a real dir is already there — don't clobber it
            Files.createSymbolicLink(dists, shared)
        }
    }
}
