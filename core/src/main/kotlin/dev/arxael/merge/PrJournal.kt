package dev.arxael.merge

import dev.arxael.util.AtomicWrite
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

/**
 * Append-only PR lifecycle journal for crash durability.
 *
 * Records SUBMIT when a PR enters the queue and DONE when it reaches ANY terminal outcome (landed-and-
 * confirmed, reverted, or bounced). On restart, [pending] returns the PRs that were SUBMITted but never
 * DONE; the orchestrator re-enqueues them. That single mechanism recovers two failure modes a mid-flight
 * crash would otherwise cause:
 *   1. queued PRs simply lost from memory; and
 *   2. (the soundness one) a PR optimistically LANDED on main whose async gate hadn't finished — it would
 *      sit on main forever UNVERIFIED. Re-enqueuing re-runs its gate (the re-merge is a no-op since it's
 *      already on main), so a crash can never leave an unverified change on main.
 *
 * Best-effort, line-oriented (tab-separated), no fsync — a trusted local daemon, and a lost tail at worst
 * means a PR is re-gated (safe) or an agent resubmits.
 */
class PrJournal(private val path: Path) {

    /** SUBMIT is CRITICAL: a write failure propagates so the orchestrator does NOT queue/land a PR it couldn't
     *  durably record (a swallowed SUBMIT would let an unjournaled PR land, then a crash would never re-gate it). */
    @Synchronized
    fun submit(pr: PullRequest) {
        Files.createDirectories(path.parent)
        Files.writeString(path, "SUBMIT\t${pr.branch}\t${pr.module ?: ""}\t${pr.agentId ?: ""}\n", CREATE, APPEND)
        maybeCompact()
    }

    /** DONE is best-effort: a lost DONE only causes a harmless re-gate on restart, never an unsound result. */
    @Synchronized
    fun done(branch: String) {
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, "DONE\t$branch\n", CREATE, APPEND)
            maybeCompact()
        } catch (_: Exception) { /* best-effort: a lost DONE just re-gates a finished PR (safe) */ }
    }

    private var appendsSinceCompact = 0

    private fun maybeCompact() {
        // Self-compact periodically so a long-lived daemon's journal can't grow unbounded (and pending() stays
        // cheap): rewrite with ONLY the still-pending PRs, dropping every SUBMIT/DONE pair.
        if (++appendsSinceCompact >= COMPACT_EVERY) compact()
    }

    private fun compact() {
        try {
            val content = pending().joinToString("") { "SUBMIT\t${it.branch}\t${it.module ?: ""}\t${it.agentId ?: ""}\n" }
            // ATOMIC replace, never truncate-in-place: a crash mid-compaction must not be able to wipe the
            // pending set (which would strand an optimistically-landed-but-unverified change on main forever).
            AtomicWrite.writeString(path, content)
            appendsSinceCompact = 0
        } catch (_: Exception) { /* best-effort: the old journal is intact (atomic), so this just retries later */ }
    }

    /** PRs that were submitted but never reached a terminal outcome, in submit order. */
    @Synchronized
    fun pending(): List<PullRequest> {
        if (!Files.exists(path)) return emptyList()
        val submitted = LinkedHashMap<String, PullRequest>()
        val done = HashSet<String>()
        for (line in runCatching { Files.readAllLines(path) }.getOrDefault(emptyList())) {
            val p = line.split("\t")
            when (p.getOrNull(0)) {
                "SUBMIT" -> if (p.size >= 4) {
                    submitted[p[1]] = PullRequest(branch = p[1], module = p[2].ifEmpty { null }, agentId = p[3].ifEmpty { null })
                }
                "DONE" -> p.getOrNull(1)?.let { done.add(it) }
            }
        }
        return submitted.filterKeys { it !in done }.values.toList()
    }

    private companion object {
        const val COMPACT_EVERY = 1000 // rewrite the journal every N appends (bounds its size + read cost)
    }
}
