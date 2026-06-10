"""Box resource sampler — full-visibility, no dark spots, straight from /proc.

Both benchmark arms are measured with the SAME system-level metrics so the comparison is
apples-to-apples regardless of how each arm lays out its processes. No external deps
(psutil/iostat not required); container JVMs are host-visible in /proc, so the per-JVM RSS and
gradle-daemon counts capture BOTH arms uniformly — which is the whole memory story.

Captured each tick: memory + swap, CPU%, load, run/blocked procs, disk IOPS/throughput/util
(the EBS chokepoint), network throughput, JVM count + total JVM RSS, gradle-daemon count.
Disk instrumentation is first-class: if provisioned EBS IOPS is the ceiling
that bends the curve, %util→100 / rising await / IOPS-pinned is what shows it.
"""
import os
import time
import threading


def _meminfo():
    m = {}
    with open("/proc/meminfo") as f:
        for line in f:
            k, _, v = line.partition(":")
            m[k] = int(v.split()[0])  # kB
    return m


def _diskstats(dev):
    with open("/proc/diskstats") as f:
        for line in f:
            p = line.split()
            if len(p) >= 14 and p[2] == dev:
                return {"reads": int(p[3]), "rd_sect": int(p[5]),
                        "writes": int(p[7]), "wr_sect": int(p[9]), "io_ticks": int(p[12])}
    return None


def _cpu_idle_total():
    with open("/proc/stat") as f:
        vals = [int(x) for x in f.readline().split()[1:]]
    return vals[3] + (vals[4] if len(vals) > 4 else 0), sum(vals)


def _procs_state():
    running = blocked = 0
    with open("/proc/stat") as f:
        for line in f:
            if line.startswith("procs_running"):
                running = int(line.split()[1])
            elif line.startswith("procs_blocked"):
                blocked = int(line.split()[1])
    return running, blocked


def _netdev():
    rx = tx = 0
    with open("/proc/net/dev") as f:
        for line in f.readlines()[2:]:
            name, _, rest = line.partition(":")
            if name.strip() == "lo":
                continue
            cols = rest.split()
            rx += int(cols[0]); tx += int(cols[8])
    return rx, tx  # bytes


def _jvms():
    """Return (jvm_count, total_rss_kb, gradle_daemon_count) across the whole box."""
    count = rss = gradle = 0
    for pid in os.listdir("/proc"):
        if not pid.isdigit():
            continue
        try:
            with open(f"/proc/{pid}/comm") as f:
                if f.read().strip() != "java":
                    continue
            count += 1
            with open(f"/proc/{pid}/status") as f:
                for line in f:
                    if line.startswith("VmRSS:"):
                        rss += int(line.split()[1]); break
            with open(f"/proc/{pid}/cmdline") as f:
                if "GradleDaemon" in f.read().replace("\0", " "):
                    gradle += 1
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            continue
    return count, rss, gradle


class Sampler:
    def __init__(self, disk_dev="nvme0n1", interval=1.0, timeseries_path=None):
        self.dev = disk_dev
        self.interval = interval
        self.samples = []
        self.timeseries_path = timeseries_path
        self._ts_file = None
        self._stop = threading.Event()
        self._thread = None
        mi = _meminfo()
        self.total_kb = mi.get("MemTotal", 0)

    def _run(self):
        import json
        if self.timeseries_path:
            self._ts_file = open(self.timeseries_path, "w")
        prev_d = _diskstats(self.dev)
        prev_idle, prev_tot = _cpu_idle_total()
        prev_rx, prev_tx = _netdev()
        prev_t = time.monotonic()
        while not self._stop.wait(self.interval):
            now = time.monotonic(); dt = now - prev_t
            mi = _meminfo()
            d = _diskstats(self.dev)
            idle, tot = _cpu_idle_total()
            rx, tx = _netdev()
            running, blocked = _procs_state()
            jvm_n, jvm_rss, gradle_n = _jvms()
            try:
                load1 = float(open("/proc/loadavg").read().split()[0])
            except Exception:
                load1 = 0.0

            total = mi.get("MemTotal", 1); avail = mi.get("MemAvailable", 0)
            swap_used = mi.get("SwapTotal", 0) - mi.get("SwapFree", 0)
            cpu = 100.0 * (1.0 - (idle - prev_idle) / (tot - prev_tot)) if tot != prev_tot else 0.0
            row = {
                "t": round(now, 2),
                "mem_used_mb": (total - avail) // 1024,
                "mem_used_pct": round(100.0 * (total - avail) / total, 1),
                "swap_used_mb": swap_used // 1024,
                "cpu_pct": round(cpu, 1),
                "load1": load1,
                "procs_running": running,
                "procs_blocked": blocked,
                "jvm_count": jvm_n,
                "jvm_rss_mb": jvm_rss // 1024,
                "gradle_daemons": gradle_n,
            }
            if d and prev_d and dt > 0:
                row["read_iops"] = round((d["reads"] - prev_d["reads"]) / dt, 1)
                row["write_iops"] = round((d["writes"] - prev_d["writes"]) / dt, 1)
                row["rd_mbps"] = round((d["rd_sect"] - prev_d["rd_sect"]) * 512 / 1e6 / dt, 1)
                row["wr_mbps"] = round((d["wr_sect"] - prev_d["wr_sect"]) * 512 / 1e6 / dt, 1)
                row["disk_util_pct"] = round(min(100.0, (d["io_ticks"] - prev_d["io_ticks"]) / (dt * 1000) * 100), 1)
            if dt > 0:
                row["net_rx_mbps"] = round((rx - prev_rx) / 1e6 / dt, 1)
                row["net_tx_mbps"] = round((tx - prev_tx) / 1e6 / dt, 1)
            self.samples.append(row)
            if self._ts_file:
                self._ts_file.write(json.dumps(row) + "\n"); self._ts_file.flush()
            prev_d, prev_idle, prev_tot, prev_rx, prev_tx, prev_t = d, idle, tot, rx, tx, now

    def start(self):
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=self.interval * 2)
        if self._ts_file:
            self._ts_file.close()

    def summary(self):
        if not self.samples:
            return {}
        def peak(k): return max((s.get(k, 0) for s in self.samples), default=0)
        def avg(k):
            xs = [s.get(k, 0) for s in self.samples if k in s]
            return round(sum(xs) / len(xs), 1) if xs else 0
        return {
            "total_ram_mb": self.total_kb // 1024,
            "peak_mem_used_mb": peak("mem_used_mb"),
            "peak_mem_used_pct": peak("mem_used_pct"),
            "peak_swap_used_mb": peak("swap_used_mb"),
            "avg_cpu_pct": avg("cpu_pct"),
            "peak_cpu_pct": peak("cpu_pct"),
            "peak_load1": peak("load1"),
            "peak_procs_running": peak("procs_running"),
            "peak_procs_blocked": peak("procs_blocked"),
            "peak_jvm_count": peak("jvm_count"),
            "peak_jvm_rss_mb": peak("jvm_rss_mb"),
            "peak_gradle_daemons": peak("gradle_daemons"),
            "peak_read_iops": peak("read_iops"),
            "peak_write_iops": peak("write_iops"),
            "peak_disk_util_pct": peak("disk_util_pct"),
            "avg_disk_util_pct": avg("disk_util_pct"),
            "peak_wr_mbps": peak("wr_mbps"),
            "peak_rd_mbps": peak("rd_mbps"),
            "peak_net_tx_mbps": peak("net_tx_mbps"),
            "peak_net_rx_mbps": peak("net_rx_mbps"),
        }
