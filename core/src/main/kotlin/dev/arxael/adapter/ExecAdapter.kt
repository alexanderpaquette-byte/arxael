package dev.arxael.adapter

import dev.arxael.config.BoxConfig
import dev.arxael.protocol.InvokeSpec
import java.nio.file.Path

/**
 * A generic, language-agnostic adapter: run an arbitrary build/test COMMAND in a worktree. This is what
 * makes the [BuildAdapter] SPI real beyond Gradle — `pytest -q`, `cargo test`, `npm test`, `make check`, etc.
 * all run through the same bounded executor + merge orchestration, no Kotlin/JVM assumptions. For the common
 * per-language case, prefer the named [CommandAdapter]s ("pytest", "cargo", …) which carry a default command;
 * use "exec" when you want to spell out the whole command yourself.
 *
 * The command is `spec.tasks + spec.args` (e.g. tasks=["pytest","-q"]). There's no warm daemon to hold —
 * most non-JVM toolchains are process-per-invocation — so each run is a fresh process in the worktree; the
 * density win here is the bounded-concurrency gate + the workflow, not a warm connection. Exit 0 = success;
 * a non-zero exit is an ordinary build failure (NOT an infra fault). Output is captured tail-bounded.
 *
 * Trust model unchanged: this runs arbitrary commands for TRUSTED local agents — no more privileged
 * than a Gradle build, which already executes arbitrary build-script code.
 */
class ExecAdapter : BuildAdapter {
    override val name = "exec"

    override fun open(worktree: Path, outputBase: Path, config: BoxConfig): AdapterSession =
        Session(worktree, config.acquireTimeoutCapMs)

    private class Session(private val worktree: Path, private val timeoutMs: Long) : AdapterSession {
        override fun run(spec: InvokeSpec, sink: (String) -> Unit): RunResult =
            ProcExec.run(worktree, spec.tasks + spec.args, timeoutMs, sink = sink)

        override fun healthy() = true
        override fun close() {}
    }
}
