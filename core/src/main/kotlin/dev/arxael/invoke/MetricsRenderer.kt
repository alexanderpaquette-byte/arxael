package dev.arxael.invoke

/**
 * Renders the daemon's live snapshots into Prometheus text exposition format (v0.0.4) — native, dependency-free
 * observability. `GET /metrics` returns this; point a Prometheus scrape at `127.0.0.1:<port>/metrics` and the
 * bundled Grafana dashboard (ops/grafana-dashboard.json) visualizes it.
 *
 * Pure on purpose (no IO, no env) so it is unit/mutation testable. The caller hands it the same `Map`s already
 * exposed on `/health` (executor + governor) and `/merge/status` (orchestrator); this projects every numeric or
 * boolean value into a metric, names it `arxael_<section>_<snake_case_key>`, and types known cumulative keys as
 * counters (`_total` suffix) and everything else as gauges.
 */
object MetricsRenderer {
    /** Snapshot keys that are monotonic cumulative counts -> emitted as Prometheus counters with a _total suffix. */
    private val COUNTER_KEYS = setOf(
        "submitted", "landed", "optLanded", "batchLanded",
        "bouncedTextual", "bouncedSemantic", "reverts", "integTests", "errors",
    )

    /**
     * @param sections (sectionPrefix -> snapshot map) pairs, e.g. "executor" to executor.snapshot().
     * @param up whether the daemon is live (always 1 when serving, but explicit for symmetry).
     */
    fun render(sections: List<Pair<String, Map<String, Any?>>>, up: Boolean = true): String {
        val sb = StringBuilder()
        sb.append("# HELP arxael_up Daemon liveness (1 = serving).\n")
        sb.append("# TYPE arxael_up gauge\n")
        sb.append("arxael_up ${if (up) 1 else 0}\n")
        for ((prefix, map) in sections) {
            for ((key, value) in map) {
                val num = toNumber(value) ?: continue // strings (e.g. bindingConstraint) are skipped, not faked
                val isCounter = key in COUNTER_KEYS
                val base = "arxael_${prefix}_${snake(key)}"
                val metric = if (isCounter) "${base}_total" else base
                sb.append("# TYPE $metric ${if (isCounter) "counter" else "gauge"}\n")
                sb.append("$metric $num\n")
            }
        }
        return sb.toString()
    }

    private fun toNumber(v: Any?): String? = when (v) {
        is Double -> when {                       // Java prints "NaN"/"Infinity"; Prometheus needs Nan/+Inf/-Inf
            v.isNaN() -> "Nan"
            v == Double.POSITIVE_INFINITY -> "+Inf"
            v == Double.NEGATIVE_INFINITY -> "-Inf"
            else -> v.toString()
        }
        is Float -> toNumber(v.toDouble())
        is Number -> v.toString()
        is Boolean -> if (v) "1" else "0"
        else -> null
    }

    /** camelCase -> snake_case, then SANITIZE to the Prometheus name charset [a-zA-Z0-9_:]. Snapshot keys are
     *  code-controlled today, but a stray key with a space/dash/dot — or, worse, a newline — would otherwise
     *  emit a malformed metric line (or inject extra exposition lines) and break the whole scrape parse. */
    private fun snake(s: String): String =
        s.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase().replace(Regex("[^a-z0-9_:]"), "_")
}
