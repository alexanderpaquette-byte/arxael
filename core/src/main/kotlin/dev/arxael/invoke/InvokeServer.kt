package dev.arxael.invoke

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.arxael.config.BoxConfig
import dev.arxael.autosize.AdaptiveGovernor
import dev.arxael.eventlog.EventLog
import dev.arxael.executor.WarmExecutor
import dev.arxael.merge.MergeService
import dev.arxael.protocol.InvokeOutcome
import dev.arxael.protocol.InvokeSpec
import dev.arxael.protocol.InvokeStatus
import dev.arxael.protocol.MergeRegisterSpec
import dev.arxael.protocol.MergeSubmitSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * The single /invoke surface. Loopback-only (single-tenant, trusted agents), so there is no
 * auth/tenancy surface by design; the threat model is "don't let a caller subvert isolation", which
 * the [ArgAllowlist] handles.
 *
 * Routes:
 *   POST /invoke    -> run an [InvokeSpec], return an [InvokeOutcome]
 *   GET  /health    -> liveness + live executor snapshot
 *   POST /shutdown  -> graceful daemon stop (loopback)
 *
 * The HTTP thread pool is sized above maxConcurrent on purpose: requests that can't get a build
 * permit must still be ACCEPTED so they can queue (or be told OVERLOADED) — the bound lives in the
 * executor, not in the socket backlog.
 */
class InvokeServer(
    private val config: BoxConfig,
    private val executor: WarmExecutor,
    private val events: EventLog,
    private val onShutdown: () -> Unit,
    private val merge: MergeService? = null,
    private val governor: AdaptiveGovernor? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val MAX_BODY_BYTES = 4 * 1024 * 1024 // 4 MB cap on a request body (specs are tiny JSON)
    private lateinit var http: HttpServer

    fun start() {
        http = HttpServer.create(InetSocketAddress("127.0.0.1", config.port), 0)
        http.executor = Executors.newFixedThreadPool(config.maxConcurrent * 2 + 4)
        http.createContext("/") { ex -> safe(ex) { handleRoot(ex) } } // catch-all: self-describing API card
        http.createContext("/health") { ex -> safe(ex) { handleHealth(ex) } }
        http.createContext("/metrics") { ex -> safe(ex) { handleMetrics(ex) } }
        http.createContext("/invoke") { ex -> safe(ex) { handleInvoke(ex) } }
        http.createContext("/warmup") { ex -> safe(ex) { handleWarmup(ex) } }
        http.createContext("/shutdown") { ex -> safe(ex) { handleShutdown(ex) } }
        if (merge != null) {
            http.createContext("/merge/register") { ex -> safe(ex) { handleMergeRegister(ex, merge) } }
            http.createContext("/merge/submit") { ex -> safe(ex) { handleMergeSubmit(ex, merge) } }
            http.createContext("/merge/status") { ex -> safe(ex) { handleMergeStatus(ex, merge) } }
        }
        http.start()
        events.emit("server_listening", mapOf("port" to config.port))
    }

    fun stop() {
        if (this::http.isInitialized) http.stop(0)
    }

    /**
     * Self-describing API card (GET /). A zero-context agent that only knows the port can introspect the
     * ENTIRE protocol here — what this is and the exact request shapes — instead of reading docs. Also the
     * fallback for any unknown path, so a lost caller is handed the contract rather than a bare 404.
     */
    private fun handleRoot(ex: HttpExchange) {
        respond(
            ex, 200,
            """
            {
              "service": "arxael-dev-kit",
              "what": "One warm, bounded build/test executor that many trusted local agents share to work on ONE project: branch -> test -> PR -> merge to main, fast and without conflicts.",
              "ifYouAreAnAgent": "To land your change: (1) POST /invoke to build/test your worktree; (2) when green, POST /merge/submit with your branch; (3) poll GET /merge/status until landed increases (or reverts/bounces if it failed). Tests run on a shared bounded executor, so expect to queue under load (status OVERLOADED = retry later).",
              "loopbackOnly": true,
              "endpoints": [
                {"method":"GET","path":"/health","desc":"liveness, live capacity (concurrencyTarget/cores), recentErrors"},
                {"method":"GET","path":"/metrics","desc":"Prometheus exposition format (scrape target); see ops/grafana-dashboard.json"},
                {"method":"POST","path":"/invoke","body":{"adapter":"gradle","worktree":"/abs/path/to/checkout","tasks":["test"],"agentId":"you"},"desc":"run a build/test in a worktree on the warm executor; status SUCCESS|FAILED|OVERLOADED|ERROR|REJECTED (REJECTED=args not on allowlist, HTTP 422)"},
                {"method":"POST","path":"/warmup","body":{"adapter":"gradle","worktree":"/abs/path/to/checkout"},"desc":"optional: pre-spawn the build daemon so your first build is warm"},
                {"method":"POST","path":"/merge/submit","body":{"branch":"my-feature","module":":app","agentId":"you"},"desc":"submit a branch-tested PR to land on main; module is its Gradle path (optional)"},
                {"method":"GET","path":"/merge/status","desc":"merge-queue stats: landed, reverts, bouncedSemantic/Textual, errors, inFlightGates"},
                {"method":"POST","path":"/merge/register","body":{"repo":"/abs/path/to/bare.git"},"desc":"operator-only: register the project; usually done for you by 'scripts/arxael up'"}
              ],
              "docs": "AGENTS.md (start here), docs/SETUP.md, docs/ARCHITECTURE.md"
            }
            """.trimIndent(),
        )
    }

    private fun handleHealth(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, """{"error":"GET only"}""")
        val snap = executor.snapshot().toMutableMap()
        snap["ok"] = true
        snap["cores"] = config.cores
        governor?.let { snap["adaptive"] = it.snapshot().toString() }
        val recent = events.recentErrors()
        snap["recentErrorCount"] = recent.size
        snap["recentErrors"] = recent.takeLast(5).toString() // last few faults, at a glance
        respond(ex, 200, json.encodeToString(mapToJson(snap)))
    }

    /**
     * Prometheus scrape target (GET /metrics). Native, dependency-free — projects the SAME live snapshots
     * shown on /health and /merge/status into the text exposition format. Point Prometheus here and load
     * ops/grafana-dashboard.json. Section prefixes: executor_*, governor_*, merge_*.
     */
    private fun handleMetrics(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, """{"error":"GET only"}""")
        val sections = mutableListOf<Pair<String, Map<String, Any?>>>()
        sections += "executor" to executor.snapshot()
        governor?.let { sections += "governor" to it.snapshot() }
        merge?.status()?.let { sections += "merge" to it }
        sections += "events" to mapOf("recentErrorCount" to events.recentErrors().size)
        respondText(ex, 200, MetricsRenderer.render(sections))
    }

    private fun handleInvoke(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, """{"error":"POST only"}""")
        val body = readBody(ex) ?: return respond(ex, 413, """{"ok":false,"error":"request body too large"}""")
        val spec = try {
            json.decodeFromString<InvokeSpec>(body)
        } catch (e: Exception) {
            return respond(ex, 400, json.encodeToString(reject("bad request: ${e.message}")))
        }
        when (val v = ArgAllowlist.check(spec)) {
            is ArgAllowlist.Rejected -> {
                events.emit("invoke_rejected", mapOf("reason" to v.reason, "agent" to spec.agentId))
                return respond(ex, 422, json.encodeToString(reject(v.reason)))
            }
            ArgAllowlist.Ok -> { /* fall through */ }
        }
        val outcome = executor.submit(spec)
        val code = when (InvokeStatus.valueOf(outcome.status)) {
            InvokeStatus.SUCCESS -> 200
            InvokeStatus.FAILED -> 200          // a red build is a valid, successful invocation
            InvokeStatus.REJECTED -> 422
            InvokeStatus.OVERLOADED -> 503
            InvokeStatus.ERROR -> 500
        }
        respond(ex, code, json.encodeToString(outcome))
    }

    private fun handleMergeRegister(ex: HttpExchange, merge: MergeService) {
        if (ex.requestMethod != "POST") return respond(ex, 405, """{"error":"POST only"}""")
        val body = readBody(ex) ?: return respond(ex, 413, """{"ok":false,"error":"request body too large"}""")
        val spec = try { json.decodeFromString<MergeRegisterSpec>(body) }
            catch (e: Exception) { return respond(ex, 400, """{"ok":false,"error":${jsonStr("bad request: ${e.message}")}}""") }
        val reg = try {
            merge.register(spec.repo, spec.forwardDeps.mapValues { it.value.toSet() }, spec.threshold, spec.gateWorktrees)
        } catch (e: IllegalArgumentException) {
            return respond(ex, 422, """{"ok":false,"error":${jsonStr(e.message ?: "register failed")}}""")
        }
        respond(ex, 200, """{"ok":true,"repo":${jsonStr(reg.repo)},"modules":${reg.modules},"gateWorktrees":${reg.gateWorktrees},"threshold":${reg.threshold}}""")
    }

    private fun handleMergeSubmit(ex: HttpExchange, merge: MergeService) {
        if (ex.requestMethod != "POST") return respond(ex, 405, """{"error":"POST only"}""")
        val body = readBody(ex) ?: return respond(ex, 413, """{"ok":false,"error":"request body too large"}""")
        val spec = try { json.decodeFromString<MergeSubmitSpec>(body) }
            catch (e: Exception) { return respond(ex, 400, """{"ok":false,"error":${jsonStr("bad request: ${e.message}")}}""") }
        return if (merge.submit(spec.branch, spec.module, spec.agentId)) {
            respond(ex, 200, """{"ok":true,"queued":${jsonStr(spec.branch)}}""")
        } else {
            respond(ex, 409, """{"ok":false,"error":"no project registered; POST /merge/register first"}""")
        }
    }

    private fun handleMergeStatus(ex: HttpExchange, merge: MergeService) {
        if (ex.requestMethod != "GET") return respond(ex, 405, """{"error":"GET only"}""")
        val s = merge.status() ?: return respond(ex, 200, """{"ok":true,"registered":false}""")
        val snap = s.toMutableMap(); snap["ok"] = true; snap["registered"] = true
        respond(ex, 200, json.encodeToString(mapToJson(snap)))
    }

    /**
     * Pre-warm a worktree's build server off the agent's critical path: this opens the warm session and
     * spawns + primes the Gradle daemon NOW, so the agent's first real build skips cold-start (the
     * per-worktree first-build tax measured in phase 7). The agent-runner calls this per worktree at setup,
     * in parallel. Empty tasks default to a cheap daemon-spawn (`help`); pass e.g. `["testClasses"]` to also
     * warm the per-worktree build cache (compiled outputs) so the first `test` is hotter.
     */
    private fun handleWarmup(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, """{"error":"POST only"}""")
        val body = readBody(ex) ?: return respond(ex, 413, """{"ok":false,"error":"request body too large"}""")
        val spec = try { json.decodeFromString<InvokeSpec>(body) }
            catch (e: Exception) { return respond(ex, 400, json.encodeToString(reject("bad request: ${e.message}"))) }
        val primed = if (spec.tasks.isEmpty()) spec.copy(tasks = listOf("help")) else spec
        when (val v = ArgAllowlist.check(primed)) {
            is ArgAllowlist.Rejected -> return respond(ex, 422, json.encodeToString(reject(v.reason)))
            ArgAllowlist.Ok -> {}
        }
        events.emit("warmup", mapOf("worktree" to primed.worktree, "tasks" to primed.tasks.toString()))
        val outcome = executor.submit(primed)
        val code = if (outcome.status == InvokeStatus.OVERLOADED.name) 503 else 200
        respond(ex, code, json.encodeToString(outcome))
    }

    private fun handleShutdown(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, """{"error":"POST only"}""")
        respond(ex, 200, """{"ok":true,"shutting_down":true}""")
        Thread { onShutdown() }.start()
    }

    /** Read the request body with a hard cap. Specs are tiny JSON; an unbounded read lets one loopback caller
     *  OOM the shared daemon (taking down every agent). Returns null if the body exceeds [MAX_BODY_BYTES]. */
    private fun readBody(ex: HttpExchange): String? {
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        val ins = ex.requestBody
        var total = 0
        while (true) {
            val n = ins.read(tmp)
            if (n < 0) break
            total += n
            if (total > MAX_BODY_BYTES) return null
            buf.write(tmp, 0, n)
        }
        return buf.toString(Charsets.UTF_8)
    }

    private fun reject(reason: String) = InvokeOutcome(
        ok = false, status = InvokeStatus.REJECTED.name, server = "-",
        queueMs = 0, runMs = 0, output = "", message = reason,
    )

    private inline fun safe(ex: HttpExchange, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            try { respond(ex, 500, """{"ok":false,"error":${jsonStr(e.message ?: "error")}}""") } catch (_: Exception) {}
        }
    }

    private fun respond(ex: HttpExchange, code: Int, body: String) =
        respondWith(ex, code, body, "application/json")

    /** Prometheus exposition format is text/plain (version 0.0.4). */
    private fun respondText(ex: HttpExchange, code: Int, body: String) =
        respondWith(ex, code, body, "text/plain; version=0.0.4; charset=utf-8")

    private fun respondWith(ex: HttpExchange, code: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    // Minimal map->json for the health snapshot (values are Int/Boolean/String only).
    private fun mapToJson(m: Map<String, Any?>): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in m) when (v) {
                is Number -> put(k, kotlinx.serialization.json.JsonPrimitive(v))
                is Boolean -> put(k, kotlinx.serialization.json.JsonPrimitive(v))
                else -> put(k, kotlinx.serialization.json.JsonPrimitive(v?.toString()))
            }
        }

    private fun jsonStr(s: String): String {
        val esc = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        return "\"$esc\""
    }
}
