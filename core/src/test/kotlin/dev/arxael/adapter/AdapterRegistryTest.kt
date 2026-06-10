package dev.arxael.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The name→adapter lookup. A miss here means /invoke can't find a registered adapter or silently resolves a wrong one. */
class AdapterRegistryTest {
    private val reg = AdapterRegistry.default()

    @Test
    fun `resolves the built-in adapters by name`() {
        assertNotNull(reg.get("gradle"))
        assertNotNull(reg.get("noop"))
        assertEquals("gradle", reg.get("gradle")!!.name)
    }

    @Test
    fun `unknown adapter resolves to null and names lists what's available`() {
        assertNull(reg.get("does-not-exist"))
        assertTrue(reg.names().containsAll(setOf("gradle", "noop")))
    }
}
