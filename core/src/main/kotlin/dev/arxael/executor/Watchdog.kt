package dev.arxael.executor

import dev.arxael.config.BoxConfig
import dev.arxael.eventlog.EventLog

/**
 * Health watchdog over the warm servers.
 *
 * Hard-won fix: a reconnect/health watchdog that starved the executor's
 * scheduler was a wedge cause. So this watchdog is deliberately starvation-proof:
 *   - it runs on ONE low-priority daemon thread, never the request path;
 *   - it only PROBES (the cheap [WorktreeServer.healthy] / busy flag) and never takes a
 *     server's run-lock or blocks on build work — a busy server is skipped, not waited on;
 *   - it does a bounded amount of work per tick (one pass over the current servers) then sleeps.
 * It observes and reports; it never contends with the scheduler for the thing being scheduled.
 */
class Watchdog(
    private val executor: WarmExecutor,
    private val events: EventLog,
    private val config: BoxConfig,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread {
            while (running) {
                // tick() probes servers + recoverUnhealthy() closes wedged connections — both CAN throw; an
                // uncaught throw here would kill the watchdog thread silently and stop ALL recovery. Survive it.
                try { tick() } catch (e: Exception) {
                    events.emit("watchdog_error", mapOf("error" to (e.message ?: e.toString())))
                }
                try { Thread.sleep(config.watchdogIntervalMs) } catch (_: InterruptedException) { break }
            }
        }.apply {
            isDaemon = true
            name = "arxael-watchdog"
            priority = Thread.MIN_PRIORITY // yield to the scheduler under load
            start()
        }
    }

    internal fun tick() {
        val (checked, unhealthy) = scan(executor.servers())
        if (unhealthy > 0) {
            events.emit("watchdog_unhealthy", mapOf("checked" to checked, "unhealthy" to unhealthy))
            // Recover off the hot path: quarantine the unhealthy NON-busy servers, drop their wedged
            // connections, and let the next invoke recreate fresh ones — never reconnect-in-place. Busy
            // servers are left for a later pass (we never wait on a running build).
            val recovered = executor.recoverUnhealthy()
            if (recovered > 0) events.emit("watchdog_recovered", mapOf("recovered" to recovered))
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    companion object {
        /** Pure probe over the servers: (checked, unhealthy), skipping busy ones (never wait on a build). */
        fun scan(servers: Collection<WorktreeServer>): Pair<Int, Int> {
            var checked = 0; var unhealthy = 0
            for (s in servers) {
                if (s.busy) continue
                checked++
                if (!s.healthy()) unhealthy++
            }
            return checked to unhealthy
        }
    }
}
