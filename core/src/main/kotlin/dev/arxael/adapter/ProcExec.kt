package dev.arxael.adapter

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Shared process runner for the non-JVM adapters ([ExecAdapter], [CommandAdapter]). Runs one command in a
 * worktree, streams tail-bounded output, and maps the result to the SPI contract:
 *   - exit 0            -> success
 *   - non-zero exit     -> ordinary build failure (NOT an infra fault) — returned, never thrown
 *   - couldn't start    -> infra fault — thrown so the executor fails closed (ERROR), not silently green
 *   - timeout           -> failure with a clear message (process force-killed)
 */
internal object ProcExec {
    const val MAX_OUTPUT_BYTES = 64 * 1024
    private const val DRAIN_JOIN_MS = 2000L // bounded wait for the drain on normal exit (a detached grandchild can't hold us)

    fun run(
        worktree: Path,
        cmd: List<String>,
        timeoutMs: Long,
        env: Map<String, String> = emptyMap(),
        sink: (String) -> Unit,
    ): RunResult {
        if (cmd.isEmpty()) return RunResult(success = false, message = "no command given (use tasks/args)")
        val out = ByteArrayOutputStream()
        return try {
            val pb = ProcessBuilder(cmd)
                .directory(worktree.toFile())
                .redirectErrorStream(true)
            if (env.isNotEmpty()) pb.environment().putAll(env)
            val p = pb.start()
            // Drain stdout on a SEPARATE thread. If we read inline (copyTo to EOF) before waitFor, a child
            // that hangs while holding stdout open blocks the read forever — waitFor (and the timeout/kill)
            // is never reached, wedging the build permit permanently. Draining off-thread lets the timeout fire.
            val drain = thread(isDaemon = true, name = "procexec-drain") {
                try { p.inputStream.copyTo(CapTail(out, MAX_OUTPUT_BYTES)) } catch (_: Exception) { /* pipe closed on kill */ }
            }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                // Kill the whole TREE, not just the direct child: destroyForcibly reaps only the immediate
                // process, but a grandchild it spawned (a daemonized test runner, a backgrounded server) keeps
                // stdout (fd 1) open, so the drain thread's read() never unblocks and the build's permit-adjacent
                // thread leaks. Killing descendants closes the pipe so the drain returns at EOF.
                destroyTree(p)
                p.waitFor(5, TimeUnit.SECONDS)
                drain.join(2000)                          // collect whatever output was buffered, bounded
                sink(out.toString(Charsets.UTF_8))
                return RunResult(success = false, message = "timed out after ${timeoutMs}ms")
            }
            // Normal exit: the direct child's pipe end is closed, so the drain returns at EOF promptly. A grandchild
            // that DETACHED (reparented to init) before the child exited can keep the pipe open — we can't reach it
            // to kill, so we DON'T block on its lifetime: the join is bounded and we proceed. That drain is a daemon
            // thread holding no permit; it ends when the grandchild does and never blocks JVM exit.
            drain.join(DRAIN_JOIN_MS)
            sink(out.toString(Charsets.UTF_8))
            val code = p.exitValue()
            RunResult(success = code == 0, message = exitMessage(code))
        } catch (e: Exception) {
            // Couldn't even start the process (bad command, missing binary) -> infra fault: fail closed.
            throw RuntimeException("failed to run ${cmd.firstOrNull()}: ${e.message}", e)
        }
    }

    /** Kill the whole process tree. destroyForcibly reaps only the immediate process; a grandchild it spawned
     *  can keep stdout open (wedging the drain) and keep running. Killing descendants first closes the pipe and
     *  stops orphaned build helpers. (A grandchild that already reparented away can't be reached here — see the
     *  normal-exit note in [run].) */
    private fun destroyTree(p: Process) {
        runCatching { p.descendants().forEach { it.destroyForcibly() } }
        p.destroyForcibly()
    }

    /** Map an exit code to a message. A child killed by a signal exits 128+N on Linux (SIGKILL=137, SIGTERM=143,
     *  SIGSEGV=139, …) — surface that distinctly from an ordinary non-zero test failure so an OOM-kill / crash of
     *  the build process is visible as such, not laundered into "your tests failed". */
    private fun exitMessage(code: Int): String? = when {
        code == 0 -> null
        code in 129..192 -> "killed by signal ${code - 128} (exit $code)"
        else -> "exit $code"
    }

    /** Keeps only the last [cap] bytes (where failures print), so huge output can't OOM the daemon. */
    private class CapTail(private val sink: ByteArrayOutputStream, private val cap: Int) : java.io.OutputStream() {
        override fun write(b: Int) { sink.write(b); trim() }
        override fun write(b: ByteArray, off: Int, len: Int) { sink.write(b, off, len); trim() }
        private fun trim() {
            if (sink.size() > cap * 2) {
                val bytes = sink.toByteArray(); sink.reset(); sink.write(bytes, bytes.size - cap, cap)
            }
        }
    }
}
