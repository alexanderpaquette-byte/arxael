package dev.arxael.merge

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure parser for the probe output. A miss here silently degrades routing (wrong closures / wrong
 * "known" set), so the tagged-line contract, the module set, dedup, and self-edge rejection are pinned.
 */
class ModuleGraphProbeTest {

    @Test
    fun `parses module set + dependency edges`() {
        val out = """
            > Configure project :
            ARXMOD :
            ARXMOD :app
            ARXMOD :core
            ARXMOD :util
            ARXDEP :app :core
            ARXDEP :app :util
            ARXDEP :core :util
            BUILD SUCCESSFUL
        """.trimIndent()
        val g = ModuleGraphProbe.parse(out)
        assertEquals(setOf(":", ":app", ":core", ":util"), g.modules)
        assertEquals(setOf(":core", ":util"), g.deps[":app"])
        assertEquals(setOf(":util"), g.deps[":core"])
    }

    @Test
    fun `an edge implies both endpoints are modules even without an ARXMOD line`() {
        val g = ModuleGraphProbe.parse("ARXDEP :app :core")
        assertEquals(setOf(":app", ":core"), g.modules, "a dep-only endpoint is still a known module")
    }

    @Test
    fun `dedups repeated edges and ignores untagged lines`() {
        val g = ModuleGraphProbe.parse("ARXDEP :app :core\nimplementation noise\nARXDEP :app :core\nARXDEP  :app   :core\n")
        assertEquals(mapOf(":app" to setOf(":core")), g.deps)
    }

    @Test
    fun `rejects self-edges and empty output yields an empty graph`() {
        ModuleGraphProbe.parse("").let { assertEquals(emptySet(), it.modules); assertEquals(emptyMap(), it.deps); }
        // a self-edge contributes the module but no dependency
        ModuleGraphProbe.parse("ARXMOD :app\nARXDEP :app :app").let {
            assertEquals(setOf(":app"), it.modules); assertEquals(emptyMap(), it.deps)
        }
    }
}
