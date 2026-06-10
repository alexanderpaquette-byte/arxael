package dev.arxael.executor

import dev.arxael.eventlog.EventLog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Kills THIS daemon's Gradle build-daemons immediately on shutdown, instead of leaving them to idle out
 * (default 120s of GB-scale RSS lingering after the substrate is gone). The Tooling API exposes no PID and
 * the daemon's cmdline doesn't carry its home — but on Linux a Gradle daemon's OPEN FILES
 * (`/proc/<pid>/fd`) point at its `GRADLE_USER_HOME` (the per-home daemon log + cache lock files). So we
 * scope precisely to daemons whose open files live under our [stateDir] — never touching unrelated Gradle
 * work on the box (the reason we don't just `pkill GradleDaemon`).
 *
 * Best-effort and Linux-specific: if `/proc` is absent (non-Linux) it no-ops and the idle-timeout still
 * bounds the daemons. Call AFTER the executor's connections are closed (daemons idle → safe to kill).
 */
object GradleDaemonReaper {
    fun reapUnder(stateDir: Path, events: EventLog? = null) {
        val proc = Paths.get("/proc")
        if (!Files.isDirectory(proc)) return
        val marker = stateDir.toAbsolutePath().normalize().toString()
        var reaped = 0
        var failed = 0
        runCatching {
            Files.list(proc).use { entries ->
                entries.forEach { p ->
                    val pid = p.fileName.toString().toIntOrNull() ?: return@forEach
                    val cmd = runCatching { Files.readString(p.resolve("cmdline")) }.getOrNull() ?: return@forEach
                    if (!cmd.contains("GradleDaemon")) return@forEach
                    val fdTargets = runCatching {
                        Files.list(p.resolve("fd")).use { fds ->
                            fds.map { fd -> runCatching { Files.readSymbolicLink(fd).toString() }.getOrDefault("") }
                                .toList()
                        }
                    }.getOrDefault(emptyList())
                    if (isOurDaemon(cmd, fdTargets, marker)) {
                        // Only count a daemon as reaped if kill actually succeeded (exit 0). A swallowed failure
                        // counted as success would hide a lingering GB-scale daemon. Track failures separately.
                        val killed = runCatching { ProcessBuilder("kill", pid.toString()).start().waitFor() == 0 }
                            .getOrDefault(false)
                        if (killed) reaped++ else failed++
                    }
                }
            }
        }
        events?.emit("gradle_daemons_reaped", mapOf("count" to reaped, "failed" to failed, "under" to marker))
    }

    /**
     * Pure scoping predicate: a process is OURS to reap iff it's a Gradle daemon ([cmdline] names the daemon
     * main class) AND at least one of its open files ([fdTargets]) lives under our [marker] (stateDir). This
     * is what keeps the reaper from ever touching unrelated Gradle work on the box.
     */
    fun isOurDaemon(cmdline: String, fdTargets: List<String>, marker: String): Boolean =
        cmdline.contains("GradleDaemon") && fdTargets.any { it.startsWith(marker) }
}
