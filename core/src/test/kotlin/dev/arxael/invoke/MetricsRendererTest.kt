package dev.arxael.invoke

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetricsRendererTest {
    @Test
    fun `keys with invalid Prometheus characters are sanitized (no malformed or injected lines)`() {
        val out = MetricsRenderer.render(
            listOf("executor" to mapOf("weird key" to 1, "has-dash" to 2, "dot.path" to 3, "inj\nected" to 4)),
        )
        val nameRe = Regex("^[a-zA-Z_:][a-zA-Z0-9_:]*$")
        for (line in out.lineSequence()) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val name = line.substringBefore(' ')
            assertTrue(nameRe.matches(name), "emitted an invalid Prometheus metric name: '$name'")
        }
        assertContains(out, "arxael_executor_weird_key")
        assertContains(out, "arxael_executor_inj_ected 4") // the newline became '_', not an injected exposition line
    }

    @Test
    fun `always emits arxael_up gauge`() {
        val out = MetricsRenderer.render(emptyList(), up = true)
        assertContains(out, "# TYPE arxael_up gauge")
        assertContains(out, "arxael_up 1")
    }

    @Test
    fun `up false renders zero`() {
        assertContains(MetricsRenderer.render(emptyList(), up = false), "arxael_up 0")
    }

    @Test
    fun `numeric values become prefixed gauges in snake case`() {
        val out = MetricsRenderer.render(listOf("executor" to mapOf("inFlight" to 3, "maxConcurrent" to 16)))
        assertContains(out, "# TYPE arxael_executor_in_flight gauge")
        assertContains(out, "arxael_executor_in_flight 3")
        assertContains(out, "arxael_executor_max_concurrent 16")
    }

    @Test
    fun `known cumulative keys are counters with a total suffix`() {
        val out = MetricsRenderer.render(listOf("merge" to mapOf("landed" to 42, "reverts" to 2)))
        assertContains(out, "# TYPE arxael_merge_landed_total counter")
        assertContains(out, "arxael_merge_landed_total 42")
        assertContains(out, "arxael_merge_reverts_total counter")
        assertContains(out, "arxael_merge_reverts_total 2")
    }

    @Test
    fun `booleans render as one and zero`() {
        val out = MetricsRenderer.render(listOf("governor" to mapOf("adaptive" to true, "stalled" to false)))
        assertContains(out, "arxael_governor_adaptive 1")
        assertContains(out, "arxael_governor_stalled 0")
    }

    @Test
    fun `non-finite doubles use Prometheus spelling, not Java's`() {
        val out = MetricsRenderer.render(listOf("governor" to mapOf(
            "a" to Double.NaN, "b" to Double.POSITIVE_INFINITY, "c" to Double.NEGATIVE_INFINITY)))
        assertContains(out, "arxael_governor_a Nan")   // not "NaN"
        assertContains(out, "arxael_governor_b +Inf")  // not "Infinity"
        assertContains(out, "arxael_governor_c -Inf")
        assertFalse(out.contains("Infinity"), "Java's Infinity spelling is invalid in Prometheus")
    }

    @Test
    fun `string values are skipped not faked`() {
        val out = MetricsRenderer.render(listOf("executor" to mapOf("bindingConstraint" to "memory", "memBound" to 9)))
        assertFalse(out.contains("binding_constraint"), "string metric must not be emitted: $out")
        assertContains(out, "arxael_executor_mem_bound 9")
    }

    @Test
    fun `every emitted metric line is preceded by a TYPE line`() {
        val out = MetricsRenderer.render(
            listOf("executor" to mapOf("inFlight" to 1), "merge" to mapOf("landed" to 5)),
        )
        // every non-comment, non-blank line must be a metric whose name appeared in a prior TYPE line
        val typed = out.lines().filter { it.startsWith("# TYPE ") }.map { it.split(" ")[2] }.toSet()
        out.lines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val name = line.substringBefore(" ")
            assertTrue(name in typed, "metric '$name' has no TYPE line")
        }
    }
}
