package dev.arxael.merge

/**
 * Change-aware test scoping: look at WHAT a PR actually changed (its git diff paths) and decide how little
 * we can soundly test, instead of testing everything or trusting the agent's declared module.
 *
 *  - [isNoTestChange]: the change touches ONLY inert files (docs, text, images, license, .gitignore) that
 *    cannot affect compilation or any test -> the gate can be skipped entirely. Allowlist by construction
 *    (only known-inert files qualify), so it is sound: anything that could affect the build — any source,
 *    build script, resource, config, even a `.properties` — falls through to "test it".
 *  - [affectedModules]: map each changed path to the subproject that owns it (longest project-dir prefix),
 *    so the gate scopes to the ACTUAL changed modules' closures. Returns null (= "test the full project",
 *    the safe fallback) if any path is outside every subproject — a root build file, `settings.gradle`,
 *    shared config — because such a change can affect anything.
 *
 * Pure (no IO) so it is fully unit/mutation testable; the orchestrator feeds it `git diff --name-only`.
 */
object ChangePolicy {
    /** File extensions that cannot affect a build or a test (lowercased). */
    private val INERT_EXT = setOf(
        ".md", ".markdown", ".txt", ".rst", ".adoc", ".text",
        ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".webp", ".pdf",
    )

    /** Whole filenames (basename) that are inert. */
    private val INERT_NAMES = setOf(
        "license", "license.txt", "notice", "authors", "contributors", "changelog", "changelog.md",
        "readme", "readme.md", "codeowners", ".gitignore", ".gitattributes", ".editorconfig",
    )

    /** Path segments that mark a build INPUT tree: a doc-extension file in here may still be a test fixture /
     *  resource the build reads, so it is NOT inert (e.g. `src/test/resources/expected.txt`). */
    private val SOURCE_TREE_SEGMENTS = setOf(
        "src", "resources", "resource", "test", "tests", "testfixtures", "fixtures", "testdata", "test-data",
    )

    /** True if [path] cannot affect compilation or any test (an inert doc/text/image/meta file). Conservative:
     *  a file inside a source/resource/test tree is never inert, because a `.txt`/`.md` there can be a build
     *  input or a test fixture — only docs OUTSIDE the build trees (README, docs/, LICENSE, …) qualify. */
    fun isInert(path: String): Boolean {
        val segments = path.split('/')
        if (segments.dropLast(1).any { it.lowercase() in SOURCE_TREE_SEGMENTS }) return false
        val p = segments.last().lowercase()
        if (p in INERT_NAMES) return true
        val dot = p.lastIndexOf('.')
        return dot >= 0 && p.substring(dot) in INERT_EXT
    }

    /** True iff there are changes and EVERY changed path is inert -> the gate can be skipped (sound). */
    fun isNoTestChange(paths: Collection<String>): Boolean =
        paths.isNotEmpty() && paths.all { isInert(it) }

    /**
     * The subproject paths owning [paths], by longest project-dir prefix. [moduleDirs] maps a module path
     * (":app:core") to its repo-relative dir ("app/core"); the root project (dir "" / ".") is excluded so a
     * root-level change can't be mis-scoped to a subproject. Returns null if ANY path falls outside every
     * subproject (root build file / shared config) — caller then tests the full project (safe). Empty input
     * or an empty [moduleDirs] also returns null (nothing to narrow with -> safe full).
     */
    fun affectedModules(paths: Collection<String>, moduleDirs: Map<String, String>): Set<String>? {
        if (paths.isEmpty()) return null
        val subs = moduleDirs.filterValues { it.isNotEmpty() && it != "." && it != "/" }
        if (subs.isEmpty()) return null
        val owned = LinkedHashSet<String>()
        for (path in paths) {
            val owner = subs.entries
                .filter { (_, dir) -> path == dir || path.startsWith("$dir/") }
                .maxByOrNull { it.value.length }
                ?: return null // a path outside every subproject -> could affect anything -> full gate
            owned.add(owner.key)
        }
        return owned
    }
}
