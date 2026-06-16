package dev.arxael.merge

import org.gradle.tooling.GradleConnector
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Discover a Gradle project's inter-module dependency graph so `/merge/register` doesn't need a hand-authored
 * `forwardDeps`. The graph drives auto-route's closure sizing, so a WRONG graph is unsound (a change's
 * dependents wouldn't be re-tested) — discovering it from the build itself is correct-by-construction.
 *
 * Mechanism: inject an init script that walks every subproject's configurations and prints each
 * `ProjectDependency` as a tagged line, run a no-op task (`help`), parse the tagged lines. Pure [parse] is
 * unit-tested; [discover] shells to Gradle via the Tooling API. On any failure it returns an empty map and
 * the caller falls back to treating every PR as full-scope (always sound, just not incremental).
 *
 * Note: uses `ProjectDependency.dependencyProject.path`, valid on the pinned Gradle 8.10.2 (deprecated in
 * 8.11+); revisit if the pinned distribution is bumped.
 */
object ModuleGraphProbe {
    /** The discovered graph: the FULL set of modules (subproject paths) AND the forward dependency edges.
     *  Reporting [modules] explicitly — not just the edges — is what lets routing require positive knowledge:
     *  a module absent from [modules] is treated as unknown (routed batched), and an empty graph (discovery
     *  failed) means EVERYTHING is unknown -> everything batched (sound). */
    data class DiscoveredGraph(
        val modules: Set<String>,
        val deps: Map<String, Set<String>>,
        /** module path (":app:core") -> repo-relative project dir ("app/core"); drives change-aware test
         *  scoping ([ChangePolicy.affectedModules]). Root project ":" maps to "". */
        val moduleDirs: Map<String, String> = emptyMap(),
    ) {
        val empty: Boolean get() = modules.isEmpty()
    }

    // Prints every subproject path + its repo-relative dir (ARXMODDIR) AND each ProjectDependency edge (ARXDEP).
    private val INIT = """
        gradle.projectsEvaluated {
            def root = rootProject.projectDir.toPath()
            rootProject.allprojects { p ->
                def rel = root.relativize(p.projectDir.toPath()).toString().replace('\\', '/')
                println "ARXMODDIR ${'$'}{p.path} ${'$'}{rel}"
                p.configurations.each { c ->
                    c.dependencies.each { d ->
                        if (d instanceof ProjectDependency) {
                            println "ARXDEP ${'$'}{p.path} ${'$'}{d.dependencyProject.path}"
                        }
                    }
                }
            }
        }
    """.trimIndent()

    /**
     * Discover the project's modules + dependency edges. Returns an empty graph if discovery fails (caller
     * then routes everything batched — fail-safe). Uses a dedicated [userHome] (so it neither pollutes
     * ~/.gradle nor leaks a 3h idle daemon — the one-shot probe daemon is bounded by [daemonIdleSec]).
     */
    fun discover(projectDir: Path, gradleHome: Path, userHome: Path, daemonIdleSec: Long = 120): DiscoveredGraph {
        val init = Files.createTempFile("arxdep", ".gradle")
        Files.writeString(init, INIT)
        Files.createDirectories(userHome)
        Files.writeString(userHome.resolve("gradle.properties"), "org.gradle.daemon.idletimeout=${daemonIdleSec * 1000}\n")
        val out = ByteArrayOutputStream()
        return try {
            GradleConnector.newConnector()
                .useInstallation(gradleHome.toFile())
                .useGradleUserHomeDir(userHome.toFile())
                .forProjectDirectory(projectDir.toFile())
                .connect().use { c ->
                    c.newBuild()
                        // --no-configuration-cache: this probe exists ONLY for the init-script's println
                        // side effects (ARXMODDIR/ARXDEP lines). A registered project with
                        // org.gradle.configuration-cache=true stores its config cache in the PROJECT dir
                        // (.gradle/configuration-cache); on the 2nd+ probe against the same projectDir Gradle
                        // REUSES that cache and SKIPS projectsEvaluated, so NO tagged lines are printed and the
                        // graph parses to ZERO modules (everything then routes BATCHED — silent loss of
                        // incremental routing). Disable it here so the callback always runs. Real gate builds
                        // keep config cache (this flag is probe-only).
                        .withArguments("--init-script", init.toString(), "-q", "--no-configuration-cache")
                        .forTasks("help")
                        .setStandardOutput(out)
                        .setStandardError(out)
                        .run()
                }
            parse(out.toString())
        } catch (e: Exception) {
            DiscoveredGraph(emptySet(), emptyMap())
        } finally {
            Files.deleteIfExists(init)
        }
    }

    /** Parse the tagged probe output into the module set + forward-dependency map + project dirs (dedup via Sets). */
    fun parse(output: String): DiscoveredGraph {
        val modules = LinkedHashSet<String>()
        val deps = HashMap<String, MutableSet<String>>()
        val dirs = HashMap<String, String>()
        for (line in output.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            when (parts.getOrNull(0)) {
                "ARXMODDIR" -> parts.getOrNull(1)?.let { m -> modules.add(m); dirs[m] = parts.getOrNull(2) ?: "" }
                "ARXMOD" -> parts.getOrNull(1)?.let { modules.add(it) } // legacy (no dir)
                "ARXDEP" -> if (parts.size >= 3 && parts[1] != parts[2]) {
                    deps.getOrPut(parts[1]) { mutableSetOf() }.add(parts[2])
                    modules.add(parts[1]); modules.add(parts[2]) // edges imply both endpoints are modules
                }
            }
        }
        return DiscoveredGraph(modules, deps, dirs)
    }
}
