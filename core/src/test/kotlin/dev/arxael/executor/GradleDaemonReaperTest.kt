package dev.arxael.executor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reaper's scoping predicate — the safety-critical part: it must reap ONLY this daemon's build daemons
 * (open files under our stateDir) and NEVER unrelated Gradle work on the box.
 */
class GradleDaemonReaperTest {
    private val marker = "/home/me/.arxael"

    @Test
    fun `ours when it is a Gradle daemon with an open file under our stateDir`() {
        assertTrue(
            GradleDaemonReaper.isOurDaemon(
                cmdline = "java ... org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.10.2",
                fdTargets = listOf("/dev/null", "$marker/worktrees/abc/gradle-user-home/daemon/8.10.2/daemon-1.out.log"),
                marker = marker,
            ),
        )
    }

    @Test
    fun `NOT ours - a Gradle daemon from another project (no open file under our stateDir)`() {
        assertFalse(
            GradleDaemonReaper.isOurDaemon(
                cmdline = "java ... org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.10.2",
                fdTargets = listOf("/home/someone-else/project/.gradle/daemon/8.10.2/daemon-9.out.log"),
                marker = marker,
            ),
        )
    }

    @Test
    fun `NOT ours - a non-Gradle JVM even if it has a file under our stateDir`() {
        assertFalse(
            GradleDaemonReaper.isOurDaemon(
                cmdline = "java -jar some-other-app.jar",
                fdTargets = listOf("$marker/events.jsonl"),
                marker = marker,
            ),
        )
    }

    @Test
    fun `NOT ours - a SIBLING instance whose stateDir shares our prefix (path boundary, not string prefix)`() {
        // "/home/me/.arxael-ci" starts-with the raw string "/home/me/.arxael" but is a DIFFERENT instance's
        // stateDir. A bare string-prefix match would reap the sibling's daemon mid-build; the boundary match must not.
        assertFalse(
            GradleDaemonReaper.isOurDaemon(
                cmdline = "java ... org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.10.2",
                fdTargets = listOf("/home/me/.arxael-ci/worktrees/x/gradle-user-home/daemon/8.10.2/daemon-2.out.log"),
                marker = marker,
            ),
        )
    }

    @Test
    fun `ours regardless of a trailing separator on the marker`() {
        assertTrue(
            GradleDaemonReaper.isOurDaemon(
                cmdline = "org.gradle.launcher.daemon.bootstrap.GradleDaemon",
                fdTargets = listOf("$marker/worktrees/abc/x.log"),
                marker = "$marker/",
            ),
        )
    }
}
