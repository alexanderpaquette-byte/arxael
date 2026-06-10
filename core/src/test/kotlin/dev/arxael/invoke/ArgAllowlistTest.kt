package dev.arxael.invoke

import dev.arxael.protocol.InvokeSpec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The /invoke allowlist is the substrate's safety boundary. It must FAIL CLOSED: anything not
 * explicitly recognised is rejected, and in particular no caller may pass flags that subvert the
 * substrate's own isolation. Each reject case below maps to a real attack on the worktree boundary.
 */
class ArgAllowlistTest {
    private lateinit var wt: java.nio.file.Path

    @BeforeTest fun setup() { wt = Files.createTempDirectory("wt") }
    @AfterTest fun teardown() { Files.deleteIfExists(wt) }

    private fun spec(
        adapter: String = "gradle",
        worktree: String = wt.toString(),
        tasks: List<String> = listOf("test"),
        args: List<String> = emptyList(),
    ) = InvokeSpec(adapter, worktree, tasks, args)

    private fun ok(s: InvokeSpec) = ArgAllowlist.check(s) is ArgAllowlist.Ok
    private fun rejectReason(s: InvokeSpec) = (ArgAllowlist.check(s) as ArgAllowlist.Rejected).reason

    @Test fun `valid spec passes`() {
        assertTrue(ok(spec(args = listOf("--quiet", "--rerun-tasks", "-Pfoo=bar"))))
    }

    @Test fun `paired value flag passes with a good value`() {
        assertTrue(ok(spec(args = listOf("--tests", "dev.arxael.C1Test"))))
    }

    @Test fun `unknown adapter name rejected`() {
        assertTrue(rejectReason(spec(adapter = "Gradle; rm -rf")).contains("adapter"))
    }

    @Test fun `missing worktree rejected`() {
        assertTrue(rejectReason(spec(worktree = "")).contains("worktree"))
    }

    @Test fun `nonexistent worktree rejected (fail closed)`() {
        assertTrue(rejectReason(spec(worktree = "/no/such/dir/xyz")).contains("does not exist"))
    }

    @Test fun `bad task token rejected`() {
        assertTrue(rejectReason(spec(tasks = listOf("test; evil"))).contains("task"))
    }

    @Test fun `a flag-shaped gradle TASK is rejected (cannot bypass the arg allowlist)`() {
        // tasks were only TASK-regex checked, which allows '-', so an isolation flag could sneak in as a task
        for (evil in listOf("--init-script", "--gradle-user-home", "-I", "-g", "--project-cache-dir")) {
            assertTrue(rejectReason(spec(tasks = listOf(evil))).contains("flag"),
                "gradle task '$evil' must be rejected")
        }
        assertTrue(ok(spec(tasks = listOf("test", ":app:core:test"))), "real gradle tasks still pass")
    }

    @Test fun `non-gradle adapters may pass tool flags as tasks`() {
        // pytest/cargo/etc. legitimately take flags as tasks and have no injected isolation to subvert
        Files.createDirectories(wt) // ensure worktree exists
        assertTrue(ok(spec(adapter = "pytest", tasks = listOf("-q", "test_x.py"))))
        assertTrue(ok(spec(adapter = "cargo", tasks = listOf("test", "--release"))))
    }

    @Test fun `subversive isolation flag rejected`() {
        // The whole point of the allowlist: a caller cannot redirect our isolation.
        for (evil in listOf("--gradle-user-home", "--project-cache-dir", "--init-script")) {
            assertTrue(rejectReason(spec(args = listOf(evil, "/tmp/evil"))).contains("disallowed"),
                "expected $evil to be rejected")
        }
    }

    @Test fun `value flag without a value rejected`() {
        assertTrue(rejectReason(spec(args = listOf("--tests"))).contains("needs a value"))
    }

    @Test fun `value flag with a shell-metachar value rejected`() {
        assertEquals(true, ArgAllowlist.check(spec(args = listOf("--tests", "pkg;rm -rf"))) is ArgAllowlist.Rejected)
    }
}
