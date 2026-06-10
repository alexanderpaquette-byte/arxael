package dev.arxael.executor

import dev.arxael.adapter.AdapterRegistry
import dev.arxael.config.BoxConfig
import dev.arxael.eventlog.EventLog
import dev.arxael.protocol.InvokeOutcome
import dev.arxael.protocol.InvokeSpec
import dev.arxael.protocol.InvokeStatus
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The warm bounded executor — the substrate's core.
 *
 * Concurrency model (non-negotiable):
 *   - A global [permits] semaphore caps in-flight invocations at cores * agentsPerCore. This is
 *     the bound that keeps the box from the per-agent cold-start RAM blowup. Excess callers QUEUE
 *     (graceful) rather than each spawning their own daemon (the collapse mode we beat). Genuine
 *     overload past [acquireTimeout] fails closed as OVERLOADED rather than wedging.
 *   - Concurrency comes from N warm [WorktreeServer]s (~cores), each serializing its own worktree.
 *     We never multiplex one server across callers.
 *   - Each worktree gets its own output-base (writable cache layer) — Gradle #8375.
 */
class WarmExecutor(
    private val config: BoxConfig,
    private val registry: AdapterRegistry,
    private val events: EventLog,
) {
    private val permits = AdjustableSemaphore(config.maxConcurrent)
    // Reserved lane: "normal" work is capped at maxConcurrent - reservedHigh, so reservedHigh global
    // permits are always free for "high"-priority (merge-gate) callers — they can't be starved by a
    // flood of branch-tests. reservedHigh=0 -> normalPermits == maxConcurrent (no reservation; default).
    private val normalPermits = AdjustableSemaphore(maxOf(1, config.maxConcurrent - config.reservedHigh))
    private val servers = ConcurrentHashMap<String, WorktreeServer>()
    private val serverSeq = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)
    private val worktreesRoot: Path = config.stateDir.resolve("worktrees")
    private val resizeLock = Any() // makes the two-semaphore resize a single atomic step (reserved-lane invariant)
    @Volatile private var shuttingDown = false // set first in shutdown(); rejects new work + blocks server resurrection

    // Recent build durations, so the overload timeout adapts to how long builds actually take.
    private val durations = DurationTracker()

    // ---- invoke-outcome counters (observability: throughput + backpressure visible on /metrics) ----
    private val invSuccess = AtomicInteger(0)
    private val invFailed = AtomicInteger(0)
    private val invOverloaded = AtomicInteger(0) // callers shed under load — the backpressure signal to watch
    private val invError = AtomicInteger(0)
    private val invRejected = AtomicInteger(0)
    private val buildRunCapHits = AtomicInteger(0) // builds cancelled at the run cap = a HANG signal (Fix A)

    /**
     * How long to wait for a permit before declaring the box overloaded (fail-closed). ADAPTIVE: as builds
     * lengthen with the project, a caller queued behind them is waiting, not overloaded — so the timeout is
     * derived from observed build time (× multiplier), FLOORED at the configured value (never shorter than
     * the operator set) and CAPPED so a genuinely wedged box still fails closed. Until builds complete it is
     * exactly the static configured timeout.
     */
    private fun effectiveAcquireTimeoutMs(): Long {
        val adaptive = durations.typicalMs() * config.acquireTimeoutMultiplier
        return adaptive.coerceIn(config.acquireTimeoutMs, maxOf(config.acquireTimeoutMs, config.acquireTimeoutCapMs))
    }

    fun snapshot(): Map<String, Any> = mapOf(
        "maxConcurrent" to config.maxConcurrent,
        "concurrencyTarget" to permits.configuredPermits(),
        "buildWorkers" to config.liveBuildWorkers.get(),
        "bindingConstraint" to config.bindingConstraint,
        "coreBound" to config.coreBound,
        "memBound" to config.memBound,
        "perBuildFootprintMb" to config.perBuildFootprintMb,
        "usableRamMb" to config.usableRamMb,
        "budgetPct" to config.budgetPct,
        "inFlight" to inFlight.get(),
        "waiting" to waitingCount(),
        "warmServers" to servers.size,
        "permitsAvailable" to permits.availablePermits(),
        // invoke outcomes (counters) — throughput + the OVERLOADED backpressure rate, visible on /metrics
        "invokeSuccess" to invSuccess.get(),
        "invokeFailed" to invFailed.get(),
        "invokeOverloaded" to invOverloaded.get(),
        "invokeError" to invError.get(),
        "invokeRejected" to invRejected.get(),
        "buildRunCapHits" to buildRunCapHits.get(),
        "buildMsTypical" to durations.typicalMs(), // gauge: EWMA of recent build times
    )

    /** In-flight builds right now. */
    fun inFlight(): Int = inFlight.get()

    /** Threads currently blocked waiting for a permit (demand signal for the adaptive governor). */
    // Sum, not max: a thread waits on AT MOST one of the two semaphores at a time (it holds the normal-lane
    // permit before it blocks on the global one), so the two queues hold DISJOINT sets of threads — the sum is
    // the true count of distinct waiters. (max would UNDER-report when both gates have waiters at once.)
    fun waitingCount(): Int = permits.queueLength + normalPermits.queueLength

    /** Current adaptive concurrency target. */
    fun concurrencyTarget(): Int = permits.configuredPermits()

    /**
     * Resize the live concurrency bound (the adaptive governor's lever). Keeps the reserved high lane
     * invariant (normal lane = target - reservedHigh). Graceful: in-flight builds are never killed.
     */
    fun setConcurrencyTarget(target: Int) {
        // Adjust BOTH semaphores under one lock so the reserved-lane invariant (normal = global - reservedHigh)
        // never has a transient window where a racing acquire sees a mismatched pair.
        synchronized(resizeLock) {
            // Never let the global bound drop to <= reservedHigh: that leaves fewer global permits than the
            // reservation, so a normal caller could take the last global permit and starve the reserved high
            // (merge-gate) lane. The config floor already enforces this for the governor, but clamp here too so
            // even a direct/buggy caller can't void the invariant.
            val eff = if (config.reservedHigh > 0) maxOf(target, config.reservedHigh + 1) else target
            permits.adjustTo(eff)
            normalPermits.adjustTo(maxOf(1, eff - config.reservedHigh))
        }
    }

    fun servers(): Collection<WorktreeServer> = servers.values

    /** Public entry: run [doSubmit] and tally the outcome by status (so /metrics shows throughput + the
     *  OVERLOADED backpressure rate without grepping events). One place catches every return path of doSubmit. */
    fun submit(spec: InvokeSpec): InvokeOutcome {
        val out = doSubmit(spec)
        when (out.status) {
            InvokeStatus.SUCCESS.name -> invSuccess
            InvokeStatus.FAILED.name -> invFailed
            InvokeStatus.OVERLOADED.name -> invOverloaded
            InvokeStatus.ERROR.name -> invError
            InvokeStatus.REJECTED.name -> invRejected
            else -> null
        }?.incrementAndGet()
        return out
    }

    private fun doSubmit(spec: InvokeSpec): InvokeOutcome {
        if (shuttingDown) {
            return InvokeOutcome(
                ok = false, status = InvokeStatus.REJECTED.name, server = "-",
                queueMs = 0, runMs = 0, output = "", message = "executor is shutting down",
            )
        }
        if (registry.get(spec.adapter) == null) {
            return InvokeOutcome(
                ok = false, status = InvokeStatus.REJECTED.name, server = "-",
                queueMs = 0, runMs = 0, output = "",
                message = "unknown adapter '${spec.adapter}'; known=${registry.names()}",
            )
        }

        val key = "${spec.adapter}::${canonical(spec.worktree)}"
        events.emit("invoke_received", mapOf("adapter" to spec.adapter, "key" to key, "agent" to spec.agentId))

        // ---- bounded concurrency gate (with reserved high-priority lane) ----
        val high = spec.priority.equals("high", ignoreCase = true) // case-insensitive: "High"/"HIGH" must not silently fall to the starvable normal lane
        val queueStart = System.nanoTime()
        val acquireTimeoutMs = effectiveAcquireTimeoutMs()
        // "normal" work first claims a lane permit (reserves capacity for high); "high" skips the lane.
        if (!high && !normalPermits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS)) {
            val queueMs = (System.nanoTime() - queueStart) / 1_000_000
            events.emit("invoke_overloaded", mapOf("key" to key, "queueMs" to queueMs, "lane" to "normal"))
            return InvokeOutcome(
                ok = false, status = InvokeStatus.OVERLOADED.name, server = "-",
                queueMs = queueMs, runMs = 0, output = "",
                message = "no normal-lane permit within ${acquireTimeoutMs}ms",
            )
        }
        val gotPermit = permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS)
        val queueMs = (System.nanoTime() - queueStart) / 1_000_000
        if (!gotPermit) {
            if (!high) normalPermits.release()
            events.emit("invoke_overloaded", mapOf("key" to key, "queueMs" to queueMs))
            return InvokeOutcome(
                ok = false, status = InvokeStatus.OVERLOADED.name, server = "-",
                queueMs = queueMs, runMs = 0, output = "",
                message = "no permit within ${acquireTimeoutMs}ms (maxConcurrent=${config.maxConcurrent})",
            )
        }

        inFlight.incrementAndGet()
        try {
            var evictRetries = 0
            while (true) {
                if (shuttingDown) {
                    // Shutdown cleared the server map; do NOT resurrect a server (it would leak, never closed).
                    return InvokeOutcome(
                        ok = false, status = InvokeStatus.ERROR.name, server = "-",
                        queueMs = queueMs, runMs = 0, output = "", message = "executor is shutting down",
                    )
                }
                val server = serverFor(key, spec)
                val sb = StringBuilder()
                val runStart = System.nanoTime()
                events.emit("invoke_start", mapOf("key" to key, "server" to server.id, "queueMs" to queueMs))
                try {
                    val result = server.run(spec) { chunk -> sb.append(chunk) }
                    val runMs = (System.nanoTime() - runStart) / 1_000_000
                    durations.record(runMs) // feed the adaptive overload timeout
                    val status = if (result.success) InvokeStatus.SUCCESS else InvokeStatus.FAILED
                    events.emit(
                        "invoke_done",
                        mapOf("key" to key, "server" to server.id, "status" to status.name, "runMs" to runMs),
                    )
                    return InvokeOutcome(
                        ok = result.success, status = status.name, server = server.id,
                        queueMs = queueMs, runMs = runMs, output = sb.toString(), message = result.message,
                    )
                } catch (e: ServerEvicted) {
                    // The warm server was evicted/recovered between fetch and run -> get a FRESH one + retry
                    // (the closed one is already off the map). Bounded so a pathological churn can't spin.
                    if (++evictRetries > MAX_EVICT_RETRIES) {
                        events.emit("invoke_error", mapOf("key" to key, "error" to e.message))
                        return InvokeOutcome(
                            ok = false, status = InvokeStatus.ERROR.name, server = server.id,
                            queueMs = queueMs, runMs = 0, output = "", message = e.message,
                        )
                    }
                    events.emit("invoke_retry_evicted", mapOf("key" to key, "server" to server.id, "attempt" to evictRetries))
                    // loop: serverFor recreates a fresh server for this key
                } catch (e: Exception) {
                    // Infra fault from the adapter — fail closed (unknown/stale state blocks). A thrown run
                    // usually means a broken/stale warm connection; healthy() is a weak sensor (returns true),
                    // so DROP this server here -> the next invoke for this worktree builds a fresh one. Wires
                    // recovery to actual faults instead of leaving a broken connection to ERROR on every call.
                    evictFaulted(key, server)
                    // A build cancelled at the run cap (Fix A) surfaces here as an infra fault. Tally it
                    // distinctly: a rising buildRunCapHits means builds are HANGING — a key soak/dogfood signal.
                    if (e.message?.contains("run cap and was cancelled") == true) buildRunCapHits.incrementAndGet()
                    val runMs = (System.nanoTime() - runStart) / 1_000_000
                    events.emit("invoke_error", mapOf("key" to key, "server" to server.id, "error" to e.message))
                    return InvokeOutcome(
                        ok = false, status = InvokeStatus.ERROR.name, server = server.id,
                        queueMs = queueMs, runMs = runMs, output = sb.toString(), message = e.message,
                    )
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable") // the loop only exits via return
        } finally {
            inFlight.decrementAndGet()
            permits.release()
            if (!high) normalPermits.release()
        }
    }

    private fun serverFor(key: String, spec: InvokeSpec): WorktreeServer {
        val server = servers[key] ?: servers.computeIfAbsent(key) {
            val adapter = registry.get(spec.adapter)!!
            val outputBase = worktreesRoot.resolve(hash(key))
            val id = "ws-${serverSeq.incrementAndGet()}-${spec.adapter}"
            events.emit("server_open", mapOf("key" to key, "server" to id, "outputBase" to outputBase.toString()))
            val session = adapter.open(Path.of(canonical(spec.worktree)), outputBase, config)
            // Pin merge-gate worktrees: their servers must stay warm under agent churn (a cold gate slows every
            // landing), and pinning also keeps their on-disk cache out of the eviction GC's reach.
            WorktreeServer(id, key, session, pinned = spec.agentId == "merge-gate")
        }
        // LRU eviction runs OUTSIDE computeIfAbsent (modifying the map inside its own mapping function violates
        // ConcurrentHashMap's contract). Called on EVERY lookup, not just cache MISSES: over-cap is a function of
        // total live keys, not miss rate, so a hot working set permanently above the cap must still be trimmed.
        // maybeEvict early-returns cheaply when at/under cap. The just-fetched server is freshest -> never a victim.
        maybeEvict()
        return server
    }

    /** Drop a server whose run threw an infra fault (likely a broken/stale connection), so the next invoke
     *  for its worktree builds a fresh one. Atomic remove-if-still-current; closes outside any map lock. The
     *  run already released the per-server lock (finally), so close() returns promptly. */
    private fun evictFaulted(key: String, server: WorktreeServer) {
        if (servers.remove(key, server)) {
            events.emit("server_evict_faulted", mapOf("key" to key, "server" to server.id))
            try { server.close() } catch (_: Exception) { /* discarding it anyway */ }
        }
    }

    /** Soft LRU eviction once warm-server count exceeds the cap, preferring idle servers. */
    private fun maybeEvict() {
        if (servers.size <= config.warmServers) return
        val victims = ArrayList<WorktreeServer>()
        synchronized(servers) {
            while (servers.size > config.warmServers) {
                val victim = servers.values.filter { !it.busy && !it.pinned }.minByOrNull { it.lastUsed() } ?: break
                servers.remove(victim.key) // off the map first: no NEW lookup routes to it
                events.emit("server_evict", mapOf("key" to victim.key, "server" to victim.id))
                victims.add(victim)
            }
        }
        // Close OUTSIDE the monitor: close() takes the server lock and can block on an in-flight run (the
        // !busy check is necessarily racy) — closing under `synchronized(servers)` would stall every other
        // map op for a whole build. A racing run() sees the `closed` flag and retries a fresh server.
        victims.forEach { try { it.close() } catch (_: Exception) { /* discarded anyway */ } }
        gcOrphanedWorktrees() // reclaim disk from output-bases the eviction just orphaned
    }

    /**
     * Reclaim disk from per-worktree output-bases (gradle homes, project/build caches) that are no longer
     * backed by a warm server — otherwise a long-running daemon working across many branches accumulates one
     * full gradle home per distinct worktree forever and eventually fills the disk. Race-safe: a directory is
     * deleted only when its key is NOT currently mapped AND it hasn't been touched within [WORKTREE_GC_TTL_MS].
     * The TTL covers the create window — [serverFor]'s `adapter.open` bumps the dir's mtime before the server
     * lands in the map, so an in-flight creation is never deleted out from under itself; once mapped, the
     * live-key check covers it. A re-seen worktree simply re-warms (cheap via the shared RO dep cache).
     */
    private fun gcOrphanedWorktrees() {
        if (!Files.isDirectory(worktreesRoot)) return
        val liveDirs = servers.keys.mapTo(HashSet()) { hash(it) }
        val cutoff = System.currentTimeMillis() - WORKTREE_GC_TTL_MS
        runCatching {
            Files.list(worktreesRoot).use { stream ->
                stream.forEach { dir ->
                    val name = dir.fileName.toString()
                    if (name in liveDirs) return@forEach
                    val touchedRecently = runCatching { Files.getLastModifiedTime(dir).toMillis() > cutoff }.getOrDefault(true)
                    if (touchedRecently) return@forEach
                    if (runCatching { dir.toFile().deleteRecursively() }.getOrDefault(false)) {
                        events.emit("worktree_gc", mapOf("dir" to name))
                    }
                }
            }
        }
    }

    /**
     * Quarantine → drain → recreate, the sanctioned recovery (never reconnect-in-place). For each
     * non-busy unhealthy server: remove it from the pool (quarantine — no new work routes to it) and close
     * its connection (it drained naturally — we only touch non-busy ones). It is NOT reconnected; the next
     * invoke for that worktree lazily creates a FRESH server via [serverFor]. Busy servers are left for a
     * later pass. Runs off the request path (the watchdog's low-priority thread). Returns the count recovered.
     */
    fun recoverUnhealthy(): Int {
        val quarantined = ArrayList<WorktreeServer>()
        synchronized(servers) {
            for (s in servers.values.toList()) {
                if (s.busy || s.healthy()) continue
                servers.remove(s.key) // quarantine: no new work routes here
                events.emit("server_recovered", mapOf("key" to s.key, "server" to s.id))
                quarantined.add(s)
            }
        }
        // Close outside the monitor (see maybeEvict): never block the map on an in-flight build; a racing
        // run() sees `closed` and retries fresh.
        quarantined.forEach { try { it.close() } catch (_: Exception) { /* discard the wedged connection */ } }
        return quarantined.size
    }

    fun shutdown() {
        shuttingDown = true // reject new submits + block server resurrection from any in-flight retry
        // Snapshot + clear UNDER the monitor, then close OUTSIDE it (see maybeEvict): close() can block on an
        // in-flight build, and holding the monitor through that would stall every concurrent map op. LOOP it: a
        // submit that passed the shuttingDown guard just before it flipped can still create one server after the
        // clear; re-snapshot until the map stays empty so no straggler server (and its daemon) leaks past shutdown.
        var rounds = 0
        while (true) {
            val toClose = synchronized(servers) {
                val snapshot = servers.values.toList()
                servers.clear()
                snapshot
            }
            toClose.forEach { try { it.close() } catch (_: Exception) { /* tearing down */ } }
            if (toClose.isEmpty() || ++rounds >= 5) break
        }
    }

    private fun canonical(p: String): String = try {
        Path.of(p).toAbsolutePath().normalize().toString()
    } catch (_: Exception) { p }

    private fun hash(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    private companion object {
        const val MAX_EVICT_RETRIES = 3 // bound the fetch-a-fresh-server retry after a racing eviction
        const val WORKTREE_GC_TTL_MS = 10 * 60 * 1000L // leave an output-base alone if touched within 10 min (create-window safety)
    }
}
