package dev.arxael.merge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChangePolicyTest {
    @Test
    fun `inert files are recognized, code and build files are not`() {
        for (p in listOf("README.md", "docs/guide.md", "LICENSE", ".gitignore", "img/logo.png", "a/b/NOTES.txt")) {
            assertTrue(ChangePolicy.isInert(p), "$p should be inert")
        }
        for (p in listOf("src/Main.kt", "app/build.gradle.kts", "settings.gradle", "x.properties",
                          "config.yml", "data.json", "a/Foo.java", "res/strings.xml")) {
            assertFalse(ChangePolicy.isInert(p), "$p must NOT be inert (could affect the build)")
        }
        // a doc-extension file INSIDE a source/resource/test tree may be a fixture the build reads -> NOT inert
        for (p in listOf("src/test/resources/expected.txt", "app/src/test/resources/golden.md",
                         "lib/src/main/resources/banner.txt", "core/test/data/fixture.json")) {
            assertFalse(ChangePolicy.isInert(p), "$p is in a build tree -> must NOT be skipped")
        }
        // …but the SAME extensions OUTSIDE a build tree are inert
        assertTrue(ChangePolicy.isInert("docs/design.txt"))
        assertTrue(ChangePolicy.isInert("module-a/README.md"))
    }

    @Test
    fun `a doc-only change is a no-test change and any code file makes it testable`() {
        assertTrue(ChangePolicy.isNoTestChange(listOf("README.md", "docs/x.md", "LICENSE")))
        assertFalse(ChangePolicy.isNoTestChange(listOf("README.md", "src/Main.kt"))) // one code file -> test
        assertFalse(ChangePolicy.isNoTestChange(emptyList())) // no changes is not a "skip"
    }

    private val dirs = mapOf(":" to "", ":app" to "app", ":app:core" to "app/core", ":lib" to "lib")

    @Test
    fun `paths map to their owning subproject by longest prefix`() {
        assertEquals(setOf(":app:core"), ChangePolicy.affectedModules(listOf("app/core/src/Main.kt"), dirs))
        assertEquals(setOf(":app"), ChangePolicy.affectedModules(listOf("app/src/Main.kt"), dirs))
        assertEquals(setOf(":app:core", ":lib"),
            ChangePolicy.affectedModules(listOf("app/core/src/A.kt", "lib/src/B.kt"), dirs))
    }

    @Test
    fun `a root-level or unknown path forces full scope (null)`() {
        assertNull(ChangePolicy.affectedModules(listOf("settings.gradle"), dirs))        // root file
        assertNull(ChangePolicy.affectedModules(listOf("app/core/x.kt", "build.gradle"), dirs)) // one root file taints
        assertNull(ChangePolicy.affectedModules(listOf("unknown/path.kt"), dirs))        // outside any subproject
        assertNull(ChangePolicy.affectedModules(emptyList(), dirs))                       // nothing -> safe full
        assertNull(ChangePolicy.affectedModules(listOf("app/x.kt"), emptyMap()))          // no graph -> safe full
    }
}
