package dev.arxael

import dev.arxael.adapter.AdapterRegistry
import dev.arxael.config.BoxConfig
import dev.arxael.eventlog.EventLog
import dev.arxael.autosize.AdaptiveGovernor
import dev.arxael.executor.GradleDaemonReaper
import dev.arxael.executor.WarmExecutor
import dev.arxael.executor.Watchdog
import dev.arxael.invoke.InvokeServer
import dev.arxael.merge.DepCacheConsolidator
import dev.arxael.merge.MergeService
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

/**
 * arxael-dev-kit daemon.
 *
 * One long-lived process holding N warm per-worktree build servers behind a single arg-allowlisted
 * /invoke surface. The whole agent fleet routes through this — instead of each agent cold-starting
 * its own build daemon in its own sandbox.
 */

/** Local API token: reuse it if present (a restart keeps the same token so live agents keep working), else
 *  generate a 256-bit hex token written 0600. Callers send it as header X-Arxael-Token (see InvokeServer). */
private fun ensureLocalToken(path: java.nio.file.Path): String {
    val existing = runCatching { Files.readString(path).trim() }.getOrNull()
    if (!existing.isNullOrEmpty()) return existing
    val tok = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    runCatching {
        Files.writeString(path, "$tok\n")
        Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
    }
    return tok
}

fun main() {
    // Bound slow/partial REQUEST bodies (slow-loris): the JDK HttpServer reaps an exchange whose request isn't
    // fully received within maxReqTime seconds, freeing the worker thread — else a stalled caller parks a
    // fixed-pool worker indefinitely and enough of them starve /health, /metrics, /invoke. We deliberately do
    // NOT set maxRspTime: a build runs inside the /invoke handler and can legitimately take minutes, and that
    // bound would abort long builds. maxReqTime only covers request RECEIPT (tiny JSON, ms on loopback). Read
    // once when the HttpServer classes initialize, so it must be set before anything creates a server.
    System.setProperty("sun.net.httpserver.maxReqTime", "60")
    val config = BoxConfig.fromEnv()
    Files.createDirectories(config.stateDir)
    // Local API token. Any local process (or a drive-by web page POSTing to 127.0.0.1 without CORS preflight)
    // can otherwise hit mutating endpoints — /shutdown, /invoke, /merge/*. Requiring header X-Arxael-Token
    // (which a web page can't set without a preflight the daemon won't answer, nor read from ~/.arxael/token)
    // raises that floor. It does NOT make arxael multi-tenant/safe against malicious agents. ARXAEL_NO_AUTH off.
    val authToken: String? = if (config.noAuth) null else ensureLocalToken(config.stateDir.resolve("token"))
    val events = EventLog(config.stateDir.resolve("events.jsonl"))

    val startEpoch = System.currentTimeMillis()
    events.emit(
        "daemon_start",
        mapOf("epoch_ms" to startEpoch, "pid" to ProcessHandle.current().pid(), "config" to config.summary()),
    )
    System.err.println("[arxael] starting | ${config.summary()}")

    // Per-worktree Gradle homes are the default: they remove the shared-home cache lock that capped
    // concurrency at ~8. To keep that safe for ALL /invoke (not just registered /merge projects), establish a
    // daemon-global read-only dep cache and a consolidator that folds freshly-downloaded deps into it — so
    // re-downloads converge to ~zero without the cross-process write lock. Only when no RO cache is pinned.
    var consolidator: DepCacheConsolidator? = null
    if (config.perWorktreeHome && config.liveRoDepCache.get() == null) {
        val sharedCache = config.stateDir.resolve("shared-deps/caches")
        Files.createDirectories(sharedCache.resolve("modules-2"))
        config.liveRoDepCache.set(sharedCache.toString())
        consolidator = DepCacheConsolidator(config.stateDir.resolve("worktrees"), sharedCache, events)
    }

    val registry = AdapterRegistry.default()
    val executor = WarmExecutor(config, registry, events)
    val watchdog = Watchdog(executor, events, config)
    val governor = AdaptiveGovernor(executor, config, events)
    val merge = MergeService(
        config, executor, events,
        gateAdapter = System.getenv("ARXAEL_MERGE_GATE_ADAPTER")?.takeIf { it.isNotBlank() } ?: "gradle",
    )

    val stopLatch = CountDownLatch(1)
    val shuttingDown = java.util.concurrent.atomic.AtomicBoolean(false)

    lateinit var server: InvokeServer
    val shutdown = {
        if (shuttingDown.compareAndSet(false, true)) {
            System.err.println("[arxael] shutting down")
            events.emit("daemon_stop", emptyMap())
            watchdog.stop()
            governor.stop()
            consolidator?.stop()
            server.stop()
            merge.shutdown()
            executor.shutdown()
            // executor connections are now closed -> our build daemons are idle; kill them immediately
            // (scoped to homes under stateDir) instead of waiting out the 120s idle-timeout.
            GradleDaemonReaper.reapUnder(config.stateDir, events)
            events.close()
            stopLatch.countDown()
        }
    }

    server = InvokeServer(config, executor, events, onShutdown = shutdown, merge = merge, governor = governor, authToken = authToken)
    if (authToken != null) System.err.println("[arxael] API token required (header X-Arxael-Token); clients read ${config.stateDir.resolve("token")} — set ARXAEL_NO_AUTH=true to disable")
    try {
        server.start()
    } catch (e: java.io.IOException) {
        // Most commonly: the port is already in use (another arxael, or any process). Fail with a CLEAR reason
        // instead of an opaque stack trace looping every RestartSec under systemd (now bounded by StartLimit*).
        System.err.println("[arxael] FATAL: cannot bind 127.0.0.1:${config.port} — ${e.message}. " +
            "Another process (or arxael) may hold it; set ARXAEL_PORT to use a different port.")
        events.emit("daemon_bind_failed", mapOf("port" to config.port, "error" to (e.message ?: e.toString())))
        events.close()
        kotlin.system.exitProcess(2)
    }
    watchdog.start()
    governor.start()
    consolidator?.start()

    Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
    System.err.println("[arxael] ready on 127.0.0.1:${config.port} | adapters=${registry.names()}")

    stopLatch.await()
}
