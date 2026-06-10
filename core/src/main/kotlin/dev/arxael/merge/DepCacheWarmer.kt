package dev.arxael.merge

import org.gradle.tooling.GradleConnector
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Populates a shared seed Gradle home with the project's resolved dependencies, so per-worktree-home builds
 * can then read them through GRADLE_RO_DEP_CACHE instead of each re-downloading (which hits Maven Central
 * 429s at scale — the documented blocker to making per-worktree homes the fast default).
 *
 * Mechanism: run a build in a dedicated seed GRADLE_USER_HOME with an init script that resolves every
 * resolvable configuration of every subproject (downloading all artifacts into the seed's modules-2). After
 * this, `<seedHome>/caches` is a complete, read-only dependency cache shared by all per-worktree builds.
 *
 * Best-effort: a failure returns null and the caller simply runs without a shared RO cache (correct, just
 * slower / re-downloading). Warming is done once per project registration, off the request path.
 */
object DepCacheWarmer {
    // Snapshot the configuration set first: resolving a configuration can create others (detached configs),
    // mutating the live collection mid-iteration -> ConcurrentModificationException. Iterate the copy.
    private val INIT = """
        gradle.projectsEvaluated {
            rootProject.allprojects { p ->
                def configs = new ArrayList(p.configurations)
                configs.each { c ->
                    if (c.canBeResolved) { try { c.resolve() } catch (ignored) {} }
                }
            }
        }
    """.trimIndent()

    /**
     * Warm [seedHome] from [projectDir]. Returns the RO dep cache path (`<seedHome>/caches`) on success,
     * or null on failure.
     */
    fun warm(projectDir: Path, seedHome: Path, gradleHome: Path, daemonIdleSec: Long = 120): Path? {
        val init = Files.createTempFile("arxwarm", ".gradle")
        Files.writeString(init, INIT)
        Files.createDirectories(seedHome)
        // Bound the one-shot warm daemon's life (Gradle's 3h default would leak a GB-scale daemon per warm).
        Files.writeString(seedHome.resolve("gradle.properties"), "org.gradle.daemon.idletimeout=${daemonIdleSec * 1000}\n")
        val out = ByteArrayOutputStream()
        return try {
            GradleConnector.newConnector()
                .useInstallation(gradleHome.toFile())
                .useGradleUserHomeDir(seedHome.toFile())
                .forProjectDirectory(projectDir.toFile())
                .connect().use { c ->
                    c.newBuild()
                        .withArguments("--init-script", init.toString(), "-q")
                        .forTasks("help")
                        .setStandardOutput(out)
                        .setStandardError(out)
                        .run()
                }
            val caches = seedHome.resolve("caches")
            if (Files.exists(caches)) caches else null
        } catch (e: Exception) {
            null
        } finally {
            Files.deleteIfExists(init)
        }
    }
}
