package dev.arxael.adapter

import dev.arxael.config.BoxConfig
import dev.arxael.protocol.InvokeSpec
import java.nio.file.Path

/**
 * The generic build/test adapter SPI — the substrate's one extension point.
 *
 * gradle is the first implementation; pytest / vitest / cargo graduate in behind THIS
 * interface with no change to the executor or the /invoke surface. The product is the
 * substrate, not any one ecosystem (README warns the name must never imply one).
 *
 * Contract:
 *  - [open] is called once per worktree-server to establish a WARM, long-lived session.
 *    It is the adapter's job to keep whatever connection it holds warm across invocations
 *    (gradle: a Tooling-API ProjectConnection — never closed per-invoke).
 *  - An [AdapterSession] is driven ONE invocation at a time (a single warm server
 *    serializes). Concurrency is achieved by opening MANY sessions, never by calling one
 *    session concurrently.
 */
interface BuildAdapter {
    val name: String

    fun open(worktree: Path, outputBase: Path, config: BoxConfig): AdapterSession
}

interface AdapterSession : AutoCloseable {
    /**
     * Run one invocation against the warm session. Build output is streamed to [sink].
     * Returns success/failure; MUST NOT throw for an ordinary build failure — only for
     * an infrastructure fault (which the executor treats as ERROR / fail-closed).
     */
    fun run(spec: InvokeSpec, sink: (String) -> Unit): RunResult

    /** Cheap liveness probe used by the watchdog. Must not block on build work. */
    fun healthy(): Boolean
}

data class RunResult(val success: Boolean, val message: String? = null)
