package dev.arxael

import kotlin.test.Test
import kotlin.test.assertTrue

class BuildInfoTest {
    @Test
    fun `version is baked from the VERSION resource, not the dev fallback`() {
        val v = BuildInfo.version
        assertTrue(v.isNotBlank(), "version must not be blank")
        // The generated resource (generateVersionResource -> /arxael-version) must be on the classpath; if the
        // baking regresses we get the bare-checkout fallback instead. (Escape hatch for an intentional bare run.)
        assertTrue(
            v != "0.0.0-dev" || System.getenv("ARXAEL_ALLOW_DEV_VERSION") != null,
            "got the dev fallback ($v) — generateVersionResource/processResources may not be wired",
        )
        assertTrue(Regex("""\d+\.\d+\.\d+.*""").matches(v), "unexpected version shape: $v")
    }
}
