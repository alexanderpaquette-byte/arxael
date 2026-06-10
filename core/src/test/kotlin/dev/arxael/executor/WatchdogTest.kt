package dev.arxael.executor

import dev.arxael.adapter.AdapterSession
import dev.arxael.adapter.RunResult
import dev.arxael.protocol.InvokeSpec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The watchdog probe: count unhealthy among non-busy servers (and report; never "fix" mid-flight).
 * The pure scan is what decides whether a fail-closed signal is emitted, so it's pinned directly.
 */
class WatchdogTest {
    private fun server(id: String, healthy: Boolean): WorktreeServer =
        WorktreeServer(id, "k-$id", object : AdapterSession {
            override fun run(spec: InvokeSpec, sink: (String) -> Unit) = RunResult(true)
            override fun healthy() = healthy
            override fun close() {}
        })

    @Test
    fun `scan counts non-busy servers and flags the unhealthy ones`() {
        val (checked, unhealthy) = Watchdog.scan(listOf(server("a", true), server("b", false), server("c", true)))
        assertEquals(3, checked)
        assertEquals(1, unhealthy)
    }

    @Test
    fun `all healthy means nothing flagged and empty means nothing checked`() {
        assertEquals(2 to 0, Watchdog.scan(listOf(server("a", true), server("b", true))))
        assertEquals(0 to 0, Watchdog.scan(emptyList()))
    }
}
