package dev.arxael.merge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Persistence for verify-then-trust: per-module confirmation counts must survive a daemon restart, or a
 * module would be re-gated FULL from scratch every restart (slower, but never unsound). Round-trip + the
 * missing-file path.
 */
class ConfirmationStoreTest {
    private val tmp: Path = Files.createTempDirectory("confstore")

    @AfterTest fun cleanup() = Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) }.let {} }

    @Test
    fun `save then load round-trips the counts`() {
        val store = ConfirmationStore(tmp.resolve("counts"))
        store.save(mapOf(":app" to 2, ":lib" to 1, ":core" to 5))
        val loaded = store.load()
        assertEquals(3, loaded.size)
        assertEquals(2, loaded[":app"])
        assertEquals(1, loaded[":lib"])
        assertEquals(5, loaded[":core"])
    }

    @Test
    fun `loading a missing file yields an empty mutable map`() {
        val loaded = ConfirmationStore(tmp.resolve("does-not-exist")).load()
        assertTrue(loaded.isEmpty())
        loaded[":x"] = 1 // must be mutable (the orchestrator mutates it live)
        assertEquals(1, loaded[":x"])
    }

    @Test
    fun `an empty save then load is empty, and a later save overwrites`() {
        val store = ConfirmationStore(tmp.resolve("c2"))
        store.save(emptyMap())
        assertTrue(store.load().isEmpty())
        store.save(mapOf(":only" to 9))
        assertEquals(mapOf(":only" to 9), store.load())
    }
}
