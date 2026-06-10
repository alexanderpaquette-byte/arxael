package dev.arxael.adapter

import dev.arxael.config.BoxConfig
import dev.arxael.protocol.InvokeSpec
import java.nio.file.Path

/**
 * A deterministic, dependency-free adapter.
 *
 * Purpose: it lets the executor + /invoke surface be smoke-tested and the benchmark
 * harness be calibrated WITHOUT a toolchain in the loop — so a red result unambiguously
 * means "the substrate is broken", never "gradle/JDK is broken". A spec arg of the form
 * `sleepMs=<n>` lets the benchmark inject a synthetic, perfectly-reproducible workload.
 */
class NoopAdapter : BuildAdapter {
    override val name = "noop"

    override fun open(worktree: Path, outputBase: Path, config: BoxConfig): AdapterSession =
        Session(worktree)

    private class Session(val worktree: Path) : AdapterSession {
        override fun run(spec: InvokeSpec, sink: (String) -> Unit): RunResult {
            val sleepMs = spec.args.firstOrNull { it.startsWith("sleepMs=") }
                ?.substringAfter("=")?.toLongOrNull() ?: 0L
            if (sleepMs > 0) Thread.sleep(sleepMs)
            sink("noop ok worktree=$worktree tasks=${spec.tasks} sleepMs=$sleepMs")
            return RunResult(success = true)
        }

        override fun healthy() = true
        override fun close() {}
    }
}
