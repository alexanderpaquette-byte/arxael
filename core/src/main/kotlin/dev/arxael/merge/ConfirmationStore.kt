package dev.arxael.merge

import java.nio.file.Files
import java.nio.file.Path

/**
 * Persists, per module, how many times an OPTIMISTIC change to it has passed a FULL (whole-project) gate.
 *
 * This is the "verify-then-trust" mechanism that closes the undeclared-coupling residual: the declared
 * dependency graph can't see reflection/resource/runtime coupling, so for a module's first few changes the
 * orchestrator gates the FULL project (which would catch a break in ANY module, declared dependent or not).
 * Once a module has been confirmed clean N times, the orchestrator trusts its narrow declared closure for
 * speed. Persisting the count means the learning survives restarts (a module proven safe stays fast).
 *
 * Best-effort, line-oriented ("<module>\t<count>"); a read/write failure just means re-confirming.
 */
class ConfirmationStore(private val path: Path) {
    fun load(): MutableMap<String, Int> {
        val m = HashMap<String, Int>()
        if (!Files.exists(path)) return m
        runCatching {
            for (line in Files.readAllLines(path)) {
                val p = line.split("\t")
                if (p.size >= 2) p[1].toIntOrNull()?.let { m[p[0]] = it }
            }
        }
        return m
    }

    fun save(counts: Map<String, Int>) {
        // atomic replace, not truncate-in-place: a crash mid-write must not erase learned counts (would just
        // re-gate-full, safe, but the whole point is the learning survives restarts)
        runCatching {
            dev.arxael.util.AtomicWrite.writeString(path, counts.entries.joinToString("") { "${it.key}\t${it.value}\n" })
        }
    }
}
