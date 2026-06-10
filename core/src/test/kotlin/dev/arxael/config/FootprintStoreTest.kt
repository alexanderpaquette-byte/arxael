package dev.arxael.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The learned-footprint persistence that makes the startup bound calibrated by history. A miss here just
 * means re-learning from the default seed (safe), but the roundtrip + bad-input handling are pinned.
 */
class FootprintStoreTest {
    private val tmp: Path = Files.createTempDirectory("arxael-fp-test")

    @AfterTest
    fun cleanup() = Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) }.let {} }

    @Test
    fun `read returns null when nothing has been written`() {
        assertNull(FootprintStore.read(tmp))
    }

    @Test
    fun `write then read roundtrips the learned footprint`() {
        FootprintStore.write(tmp, 2200)
        assertEquals(2200, FootprintStore.read(tmp))
        FootprintStore.write(tmp, 1800) // a later, lighter learning overwrites
        assertEquals(1800, FootprintStore.read(tmp))
    }

    @Test
    fun `read rejects garbage and non-positive values`() {
        Files.writeString(tmp.resolve("learned-footprint-mb"), "not-a-number")
        assertNull(FootprintStore.read(tmp))
        FootprintStore.write(tmp, 0)
        assertNull(FootprintStore.read(tmp)) // 0 is not a usable footprint
    }
}
