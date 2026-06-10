package dev.arxael.eventlog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Append-only JSONL event log — the substrate's single source of truth for what happened.
 *
 * Append-only (never rewritten) so a crash mid-write loses at most the last line, and so the
 * benchmark can replay the exact sequence of invocations/wedges after the fact. Writes are
 * serialized + flushed per line; the daemon emits far fewer events than it runs build work,
 * so the lock is never on the hot path.
 *
 * A monotonic clock stamps each event (relative ms since daemon start) so durations are
 * immune to wall-clock skew; the absolute start epoch is recorded once in `daemon_start`.
 */
class EventLog(path: Path) : AutoCloseable {
    private val writer: BufferedWriter = Files.newBufferedWriter(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.APPEND,
    )
    private val lock = Any()
    private val startNanos = System.nanoTime()
    @Volatile private var closed = false // in-flight invokes can emit after shutdown closes the writer

    // Bounded ring of recent FAULT events, so /health can show the last few problems at a glance without
    // grepping the log file. The log is the full audit trail; this is the quick "anything wrong lately?".
    private val recentErrors = ArrayDeque<String>()

    fun emit(type: String, fields: Map<String, Any?> = emptyMap()) {
        if (closed) return // shutdown closed the log; a late in-flight emit is a clean no-op (not a swallowed throw)
        val obj: JsonObject = buildJsonObject {
            put("t_ms", JsonPrimitive((System.nanoTime() - startNanos) / 1_000_000))
            put("event", JsonPrimitive(type))
            for ((k, v) in fields) put(k, toPrimitive(v))
        }
        val line = Json.encodeToString(JsonObject.serializer(), obj)
        synchronized(lock) {
            // Best-effort: a write/flush failure (disk full, etc.) must NOT propagate and kill the caller —
            // emit() is called from the hot path and from background loops. The in-memory ring still updates.
            try {
                writer.write(line)
                writer.newLine()
                writer.flush()
            } catch (_: Exception) { /* swallow: the log is best-effort, never a fault source itself */ }
            if (isFault(type)) {
                recentErrors.addLast(line)
                while (recentErrors.size > MAX_RECENT_ERRORS) recentErrors.removeFirst()
            }
        }
    }

    /** Any event type signalling a fault — captured into the recent-errors ring for /health. */
    private fun isFault(type: String): Boolean =
        type.contains("error") || type.contains("failed") || type == "invoke_overloaded"

    /** The most recent fault events (newest last), bounded. Snapshot copy — safe to read concurrently. */
    fun recentErrors(): List<String> = synchronized(lock) { recentErrors.toList() }

    private fun toPrimitive(v: Any?): JsonPrimitive = when (v) {
        null -> JsonPrimitive(null as String?)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    override fun close() {
        synchronized(lock) { closed = true; writer.flush(); writer.close() }
    }

    companion object {
        private const val MAX_RECENT_ERRORS = 20
    }
}
