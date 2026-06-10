package dev.arxael.eventlog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventLogTest {
    @Test fun `emits one append-only json line per event with a monotonic stamp and fields`() {
        val dir = Files.createTempDirectory("evt")
        val path = dir.resolve("events.jsonl")
        EventLog(path).use { log ->
            log.emit("daemon_start", mapOf("pid" to 1234, "ok" to true))
            log.emit("invoke_done", mapOf("server" to "ws-1", "runMs" to 42))
        }
        val lines = Files.readAllLines(path)
        assertEquals(2, lines.size)

        val first = Json.parseToJsonElement(lines[0]) as JsonObject
        assertEquals("daemon_start", first["event"]!!.jsonPrimitive.content)
        assertEquals("1234", first["pid"]!!.jsonPrimitive.content)
        assertTrue(first.containsKey("t_ms"), "every event carries a relative monotonic stamp")

        val second = Json.parseToJsonElement(lines[1]) as JsonObject
        assertEquals("invoke_done", second["event"]!!.jsonPrimitive.content)
        assertEquals("ws-1", second["server"]!!.jsonPrimitive.content)
    }
}
