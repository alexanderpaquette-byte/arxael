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
                p.destroyForcibly()                       // closing the pipe unblocks the drain thread
                p.waitFor(5, TimeUnit.SECONDS)
                drain.join(2000)                          // collect whatever output was buffered, bounded
                sink(out.toString(Charsets.UTF_8))
                return RunResult(success = false, message = "timed out after ${timeoutMs}ms")
            }
            drain.join(5000)                              // process exited -> pipe closed -> drain returns; sync before read
            sink(out.toString(Charsets.UTF_8))
            val code = p.exitValue()
            RunResult(success = code == 0, message = if (code == 0) null else "exit $code")
        } catch (e: Exception) {
            // Couldn't even start the process (bad command, missing binary) -> infra fault: fail closed.
            throw RuntimeException("failed to run ${cmd.firstOrNull()}: ${e.message}", e)
        }
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
