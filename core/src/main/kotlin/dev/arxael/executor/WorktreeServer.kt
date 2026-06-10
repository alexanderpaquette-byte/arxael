package dev.arxael.executor

import dev.arxael.adapter.AdapterSession
import dev.arxael.adapter.RunResult
import dev.arxael.protocol.InvokeSpec
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * One warm server bound to one worktree.
 *
 * Embodies the core invariant directly: a single warm server handles AT MOST ONE invocation at a
 * time (documented Bazel/Gradle behavior — one process serializes). The [lock] makes that explicit
 * rather than relying on callers to behave. Concurrency comes from having MANY of these, never from
 * driving one concurrently.
 */
class WorktreeServer(
    val id: String,
    val key: String,
    private val session: AdapterSession,
) {
    private val lock = ReentrantLock()
    private val lastUsedNanos = AtomicLong(System.nanoTime())
    @Volatile private var closed = false

    val busy: Boolean get() = lock.isLocked

    fun lastUsed(): Long = lastUsedNanos.get()

    fun run(spec: InvokeSpec, sink: (String) -> Unit): RunResult {
        lock.lock()
        try {
            // A caller can hold a reference to this server fetched just before eviction/recovery closed it
            // (the close happens under this same lock). Detect that here — under the lock — so we NEVER run
            // against a torn-down session; the executor catches [ServerEvicted] and retries with a fresh one.
            if (closed) throw ServerEvicted("server $id (key=$key) was evicted before this invocation ran")
            val r = session.run(spec, sink)
            lastUsedNanos.set(System.nanoTime())
            return r
        } finally {
            lock.unlock()
        }
    }

    fun healthy(): Boolean = session.healthy()

    /** Tear down the warm session. The ONLY place the warm connection is closed. Sets [closed]
     *  under the lock first, so any concurrent [run] that already holds — or is about to take — the lock is
     *  serialized: it either finished before us, or sees [closed] and bails to a fresh server. */
    fun close() {
        lock.lock()
        try { closed = true; session.close() } finally { lock.unlock() }
    }
}

/** Thrown by [WorktreeServer.run] when the server was evicted/recovered out from under the caller; the
 *  executor responds by re-fetching a fresh server and retrying (the closed one is already off the map). */
class ServerEvicted(message: String) : RuntimeException(message)
