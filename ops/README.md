# ops — native Prometheus + Grafana

The daemon exposes its live state as Prometheus metrics **natively** — no exporter, no sidecar, no extra
dependency. `GET /metrics` on the loopback port renders the same snapshots you see on `/health` and
`/merge/status` in the text exposition format.

## 0. One command (Docker) — stand it up + watch it

```bash
ops/observe-up.sh        # Prometheus :9090 + Grafana :3000, dashboard auto-loaded, scraping the local daemon
ops/observe-down.sh      # tear the stack down (leaves the daemon running)
```

The daemon binds **loopback only** (trusted-agents model), so watch Grafana through an SSH tunnel from your
machine — no port is exposed:

```bash
ssh -L 3000:localhost:3000 <user>@<box>      # then open http://localhost:3000 (anonymous read-only; admin/admin to edit)
```

`observe-up.sh` provisions the datasource + dashboard automatically (no import wizard). It scrapes
`ARXAEL_PORT` / `~/.arxael/port` / `8723`. Prefer the manual path below if you already run Prometheus/Grafana.

## 1. Scrape it

```bash
# the daemon must be running (scripts/arxael up); default port 8723
curl -s 127.0.0.1:8723/metrics | head

prometheus --config.file=ops/prometheus.yml
```

`ops/prometheus.yml` scrapes `127.0.0.1:8723/metrics` every 5s. The daemon binds **loopback only**
(trust model), so run Prometheus on the same box, or scrape through an SSH tunnel:
`ssh -L 8723:127.0.0.1:8723 <box>`.

## 2. Visualize it

Import `ops/grafana-dashboard.json` into Grafana (Dashboards → Import), pick your Prometheus datasource.
Panels: daemon up, in-flight builds, waiting (demand), concurrency target vs ceiling, learned per-build
memory footprint, merge throughput (landed/optimistic/batched per min), time-to-land p50, in-flight gates,
and reverts/bounces/errors.

## 3. What's exported

Metric names are `arxael_<section>_<snake_case_key>`. Sections: `executor_*` (capacity/demand/memory),
`governor_*` (adaptive sizing), `merge_*` (the merge orchestrator), `events_*` (recent fault count), plus
`arxael_up`. Cumulative counts (`landed`, `reverts`, `bounced_*`, `errors`, …) are Prometheus **counters**
with a `_total` suffix; everything else is a **gauge**. The renderer is
`core/.../invoke/MetricsRenderer.kt` (pure + unit-tested in `MetricsRendererTest`).

These are the same numbers the project uses to decide what's actually better — throughput (`landed/min`),
time-to-land, revert rate — so you can watch a real agent fleet prove (or disprove) a change live.
