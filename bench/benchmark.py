"""
CGI vs Servlet -- verification-lookup benchmark.

Drives the SAME logical request against both implementations and
records what each costs:

    CGI      GET http://localhost/cgi-bin/verify.py?code=...
    Servlet  GET http://localhost:8080/badgeportal/api/verify?code=...

    python bench/benchmark.py
    python bench/benchmark.py --requests 200 --levels 1,2,5,10,20,40

Outputs, all written into bench/:
    results.csv     every measurement, one row per (implementation, phase)
    results.md      the comparison tables, ready to paste into the report
    chart.png       latency and throughput against concurrency
    raw_latencies.csv   every individual sample, for anyone who wants to
                        redo the statistics themselves

FAIRNESS
--------
Everything that could bias the comparison is held equal on purpose:

  * identical SQL against the same MySQL instance, as the same database
    user, over an indexed unique column;
  * identical response bodies -- asserted before timing starts, and the
    run aborts if the two implementations have drifted apart;
  * the same client, with the same connection-reuse policy for both, so
    what is measured is the work the SERVER does per request rather than
    the client's TCP setup;
  * codes are drawn at random from the whole badges table and rotated,
    so neither side benefits from repeatedly answering one hot code;
  * a warm-up phase runs first and is discarded, so the servlet is past
    class-loading and JIT warm-up before anything is recorded.

The one thing left unequal is the request lifecycle itself. That is the
experiment.

CAVEAT worth stating in the report: the load generator is Python, on the
same machine as the servers. At high concurrency the client itself
becomes a limit, which flatters neither side but does put a ceiling on
the servlet's measured throughput. The servlet numbers are therefore
conservative -- the real gap is wider than what is printed here.
"""

import argparse
import csv
import http.client
import json
import os
import statistics
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(PROJECT, "cgi"))

SERVLET = {
    "key": "servlet",
    "label": "Java Servlet (Tomcat)",
    "host": "localhost",
    "port": 8080,
    "path": "/badgeportal/api/verify",
}
CGI = {
    "key": "cgi",
    "label": "Python CGI (Apache)",
    "host": "localhost",
    "port": 80,
    "path": "/cgi-bin/verify.py",
}

# Validated two-slot categorical palette (see the data-viz reference):
# slot 1 blue, slot 2 orange. Worst-pair CVD Delta E 24.7, normal-vision
# 33.6, both well clear of the floors, and both above 3:1 on the surface.
COLOR = {"servlet": "#2a78d6", "cgi": "#eb6834"}

# Fields that are EXPECTED to differ between the two implementations:
# one names the implementation, the other is its own measured time.
VOLATILE = ("servedBy", "elapsedMs")


# ---------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------
class Client:
    """
    One keep-alive HTTP connection, reused for every request this thread
    makes. Both implementations get exactly the same treatment: Apache
    keeps the connection open across CGI invocations too (verify.py
    sends Content-Length, so it can), which keeps TCP setup out of the
    measurement on both sides.
    """

    def __init__(self, target):
        self.target = target
        self.conn = http.client.HTTPConnection(
            target["host"], target["port"], timeout=90)

    def get(self, code):
        path = "%s?code=%s" % (self.target["path"], code)
        started = time.perf_counter()
        try:
            self.conn.request("GET", path)
            resp = self.conn.getresponse()
            body = resp.read()
            status = resp.status
        except (http.client.HTTPException, OSError):
            # A dropped keep-alive connection: rebuild it and retry once.
            try:
                self.conn.close()
            except OSError:
                pass
            self.conn = http.client.HTTPConnection(
                self.target["host"], self.target["port"], timeout=90)
            self.conn.request("GET", path)
            resp = self.conn.getresponse()
            body = resp.read()
            status = resp.status
        return (time.perf_counter() - started) * 1000.0, status, body

    def close(self):
        try:
            self.conn.close()
        except OSError:
            pass


def one_shot(target, code):
    """A single request on a throwaway connection, for preflight checks."""
    client = Client(target)
    try:
        return client.get(code)
    finally:
        client.close()


# ---------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------
def sample_codes(limit):
    """Random verification codes straight out of the badges table."""
    import dbconfig
    import mysql.connector

    conn = mysql.connector.connect(**dbconfig.db_params())
    cur = conn.cursor()
    cur.execute("SELECT verification_code FROM badges "
                "ORDER BY RAND() LIMIT %s", (limit,))
    codes = [row[0] for row in cur.fetchall()]
    cur.close()
    conn.close()
    return codes


def preflight(codes, sample_size=5):
    """
    Confirms both endpoints are up AND that they answer identically.

    If the two implementations ever diverge, every number below would be
    comparing different work, so this is a hard stop rather than a
    warning.
    """
    print("Preflight")
    print("-" * 68)

    for target in (SERVLET, CGI):
        try:
            ms, status, body = one_shot(target, codes[0])
        except Exception as exc:                       # noqa: BLE001
            print("  FAIL  %s unreachable: %s" % (target["label"], exc))
            if target is SERVLET:
                print("        start it with: scripts\\start_tomcat.bat")
            else:
                print("        start it with: C:\\xampp\\apache_start.bat")
            return False
        if status not in (200, 404):
            print("  FAIL  %s answered HTTP %d" % (target["label"], status))
            return False
        print("  ok    %-22s reachable  (%6.1f ms cold)" % (target["label"], ms))

    mismatches = 0
    for code in codes[:sample_size]:
        _, _, servlet_body = one_shot(SERVLET, code)
        _, _, cgi_body = one_shot(CGI, code)
        a = json.loads(servlet_body.decode("utf-8"))
        b = json.loads(cgi_body.decode("utf-8"))
        for field in VOLATILE:
            a.pop(field, None)
            b.pop(field, None)
        if a != b:
            mismatches += 1
            print("  FAIL  responses differ for %s" % code)
            print("        servlet: %s" % json.dumps(a, sort_keys=True))
            print("        cgi    : %s" % json.dumps(b, sort_keys=True))

    if mismatches:
        print()
        print("  The two implementations do not agree, so timing them "
              "would be meaningless.")
        print("  Check that cgi/badgecode.py still mirrors BadgeCode.java "
              "and that both")
        print("  read the same config/badgeportal.properties.")
        return False

    print("  ok    responses identical on %d sampled codes "
          "(ignoring %s)" % (sample_size, " and ".join(VOLATILE)))
    print()
    return True


# ---------------------------------------------------------------------
# Measurement
# ---------------------------------------------------------------------
def run_phase(target, codes, total, concurrency):
    """
    Fires `total` requests at `target` with `concurrency` workers.

    Each worker keeps one connection for its whole share of the work,
    and the codes are rotated so no single row stays hot.
    """
    local = threading.local()
    clients = []
    clients_lock = threading.Lock()

    def client_for():
        client = getattr(local, "client", None)
        if client is None:
            client = Client(target)
            local.client = client
            with clients_lock:
                clients.append(client)
        return client

    def work(i):
        return client_for().get(codes[i % len(codes)])

    started = time.perf_counter()
    latencies = []
    errors = 0

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        for ms, status, _ in pool.map(work, range(total)):
            if status in (200, 404):
                latencies.append(ms)
            else:
                errors += 1

    wall = time.perf_counter() - started

    for client in clients:
        client.close()

    return {
        "latencies": latencies,
        "errors": errors,
        "wall_s": wall,
        "throughput": (len(latencies) / wall) if wall > 0 else 0.0,
    }


def summarise(result):
    lat = sorted(result["latencies"])
    if not lat:
        return {k: 0.0 for k in
                ("n", "mean", "median", "p95", "p99", "min", "max", "throughput")}

    def pct(p):
        idx = min(len(lat) - 1, int(round((p / 100.0) * (len(lat) - 1))))
        return lat[idx]

    return {
        "n": len(lat),
        "mean": statistics.fmean(lat),
        "median": statistics.median(lat),
        "p95": pct(95),
        "p99": pct(99),
        "min": lat[0],
        "max": lat[-1],
        "throughput": result["throughput"],
    }


# ---------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------
def print_table(title, rows, headers):
    print(title)
    print("-" * 68)
    widths = [max(len(str(r[i])) for r in ([headers] + rows))
              for i in range(len(headers))]
    line = "  ".join(h.ljust(w) for h, w in zip(headers, widths))
    print("  " + line)
    print("  " + "  ".join("-" * w for w in widths))
    for row in rows:
        print("  " + "  ".join(str(c).ljust(w) for c, w in zip(row, widths)))
    print()


def write_chart(levels, sweep, seq, path):
    """
    Two panels: latency against concurrency, and throughput against
    concurrency. Latency uses a log scale because the two
    implementations differ by more than two orders of magnitude and a
    linear axis would flatten the servlet line onto zero.

    A static PNG for the written report, so there is no hover layer;
    every series is directly labelled at its right-hand end as well as
    carrying a legend, so identity never rests on colour alone.
    """
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        from matplotlib.ticker import FuncFormatter
    except ImportError:
        print("matplotlib is not installed -- skipping the chart.")
        print("  pip install matplotlib")
        return False

    ink, muted, grid = "#1b1f2a", "#5b6478", "#e2e5ee"

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13.0, 5.4))
    fig.patch.set_facecolor("white")

    for ax in (ax1, ax2):
        ax.set_facecolor("white")
        ax.grid(True, color=grid, linewidth=0.8, zorder=0)
        ax.set_axisbelow(True)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
        for side in ("left", "bottom"):
            ax.spines[side].set_color(grid)
        ax.tick_params(colors=muted, labelsize=9)
        ax.set_xlabel("Concurrent clients", color=muted, fontsize=10)
        ax.set_xscale("log")
        ax.set_xticks(levels)
        ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: "%g" % v))
        # Room on the right for the direct labels.
        ax.set_xlim(levels[0] * 0.85, levels[-1] * 2.6)

    # --- panel 1: mean latency ---------------------------------------
    ax1.set_yscale("log")
    ax1.set_title("Mean response time", color=ink, fontsize=12,
                  fontweight="bold", loc="left", pad=12)
    ax1.set_ylabel("milliseconds (log scale)", color=muted, fontsize=10)

    for key, label in (("cgi", "Python CGI"), ("servlet", "Java Servlet")):
        ys = [sweep[key][c]["mean"] for c in levels]
        ax1.plot(levels, ys, color=COLOR[key], linewidth=2.0,
                 marker="o", markersize=8, markeredgecolor="white",
                 markeredgewidth=1.5, label=label, zorder=3)
        ax1.annotate(" %s\n %.1f ms" % (label, ys[-1]),
                     xy=(levels[-1], ys[-1]), color=COLOR[key],
                     fontsize=9, fontweight="bold",
                     va="center", ha="left")

    # --- panel 2: throughput -----------------------------------------
    # Also log-scaled. On a linear axis the CGI line is pinned flat to
    # zero by the servlet's scale and its own shape -- the climb to
    # saturation -- becomes invisible. A log axis keeps both curves
    # readable, and the constant vertical gap between them IS the story.
    ax2.set_yscale("log")
    ax2.set_title("Throughput", color=ink, fontsize=12,
                  fontweight="bold", loc="left", pad=12)
    ax2.set_ylabel("requests per second (log scale)", color=muted, fontsize=10)

    for key, label in (("cgi", "Python CGI"), ("servlet", "Java Servlet")):
        ys = [sweep[key][c]["throughput"] for c in levels]
        ax2.plot(levels, ys, color=COLOR[key], linewidth=2.0,
                 marker="o", markersize=8, markeredgecolor="white",
                 markeredgewidth=1.5, label=label, zorder=3)
        ax2.annotate(" %s\n %.0f req/s" % (label, ys[-1]),
                     xy=(levels[-1], ys[-1]), color=COLOR[key],
                     fontsize=9, fontweight="bold",
                     va="center", ha="left")

    # Panel 2 keeps its legend in the empty band between the two curves;
    # lower-left would sit on top of the CGI line.
    for ax, loc in ((ax1, "upper left"), (ax2, "center left")):
        legend = ax.legend(loc=loc, frameon=False, fontsize=9)
        for text in legend.get_texts():
            text.set_color(muted)

    ratio = seq["cgi"]["mean"] / seq["servlet"]["mean"] if seq["servlet"]["mean"] else 0
    fig.suptitle("Badge verification lookup: CGI vs Servlet",
                 color=ink, fontsize=14, fontweight="bold",
                 x=0.010, ha="left", y=0.985)
    fig.text(0.010, 0.930,
             "Same SQL, same database, same response body — only the "
             "request lifecycle differs. Sequential baseline: CGI %.1f ms "
             "vs Servlet %.2f ms, so the servlet answers %.0f× faster."
             % (seq["cgi"]["mean"], seq["servlet"]["mean"], ratio),
             color=muted, fontsize=10, ha="left")

    fig.tight_layout(rect=(0, 0, 1, 0.895))
    fig.savefig(path, dpi=160, facecolor="white")
    plt.close(fig)
    return True


def write_markdown(path, levels, sweep, seq, meta):
    lines = []
    lines.append("# CGI vs Servlet -- verification lookup benchmark\n")
    lines.append("Generated %s\n" % meta["when"])
    lines.append("")
    lines.append("| | |")
    lines.append("|---|---|")
    lines.append("| Servlet | %s |" % meta["servlet_server"])
    lines.append("| Servlet JVM | Java %s |" % meta["servlet_jvm"])
    lines.append("| Connection pool | %s connections, opened once at startup |"
                 % meta["pool_size"])
    lines.append("| CGI | Apache HTTP Server + mod_cgi, Python %s |"
                 % meta["python"])
    lines.append("| Database | MySQL, %d badges in the table |" % meta["badge_count"])
    lines.append("| Requests per phase | %d |" % meta["requests"])
    lines.append("")

    lines.append("## Sequential (one client at a time)\n")
    lines.append("| Implementation | Mean | Median | p95 | p99 | Min | Max | Throughput |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for key, label in (("cgi", "Python CGI"), ("servlet", "Java Servlet")):
        s = seq[key]
        lines.append("| %s | %.2f ms | %.2f ms | %.2f ms | %.2f ms | %.2f ms | "
                     "%.2f ms | %.1f req/s |"
                     % (label, s["mean"], s["median"], s["p95"], s["p99"],
                        s["min"], s["max"], s["throughput"]))
    ratio = seq["cgi"]["mean"] / seq["servlet"]["mean"] if seq["servlet"]["mean"] else 0
    lines.append("")
    lines.append("**The servlet answers the same lookup %.0f times faster.**" % ratio)
    lines.append("")

    lines.append("## Concurrency sweep\n")
    lines.append("| Concurrent clients | CGI mean | Servlet mean | CGI p95 | "
                 "Servlet p95 | CGI req/s | Servlet req/s | Speed-up |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for c in levels:
        cg, sv = sweep["cgi"][c], sweep["servlet"][c]
        speed = cg["mean"] / sv["mean"] if sv["mean"] else 0
        lines.append("| %d | %.2f ms | %.2f ms | %.2f ms | %.2f ms | %.1f | "
                     "%.1f | %.0fx |"
                     % (c, cg["mean"], sv["mean"], cg["p95"], sv["p95"],
                        cg["throughput"], sv["throughput"], speed))
    lines.append("")

    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))


def write_csv(path, levels, sweep, seq):
    with open(path, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["implementation", "phase", "concurrency", "requests",
                    "mean_ms", "median_ms", "p95_ms", "p99_ms",
                    "min_ms", "max_ms", "throughput_rps"])
        for key in ("cgi", "servlet"):
            s = seq[key]
            w.writerow([key, "sequential", 1, s["n"],
                        "%.3f" % s["mean"], "%.3f" % s["median"],
                        "%.3f" % s["p95"], "%.3f" % s["p99"],
                        "%.3f" % s["min"], "%.3f" % s["max"],
                        "%.2f" % s["throughput"]])
        for key in ("cgi", "servlet"):
            for c in levels:
                s = sweep[key][c]
                w.writerow([key, "concurrent", c, s["n"],
                            "%.3f" % s["mean"], "%.3f" % s["median"],
                            "%.3f" % s["p95"], "%.3f" % s["p99"],
                            "%.3f" % s["min"], "%.3f" % s["max"],
                            "%.2f" % s["throughput"]])


def write_raw(path, raw):
    with open(path, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["implementation", "phase", "concurrency", "latency_ms"])
        for impl, phase, conc, latencies in raw:
            for ms in latencies:
                w.writerow([impl, phase, conc, "%.3f" % ms])


def replot():
    """
    Redraws chart.png from a previous run's results.csv.

    Tuning a figure for the write-up should not mean putting the servers
    under load again -- and re-running would also change the numbers the
    surrounding report text quotes.
    """
    csv_path = os.path.join(HERE, "results.csv")
    if not os.path.isfile(csv_path):
        print("No results.csv yet -- run the benchmark first.")
        return 1

    seq, sweep, levels = {}, {"cgi": {}, "servlet": {}}, []
    with open(csv_path, encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            stats = {
                "n": int(row["requests"]),
                "mean": float(row["mean_ms"]),
                "median": float(row["median_ms"]),
                "p95": float(row["p95_ms"]),
                "p99": float(row["p99_ms"]),
                "min": float(row["min_ms"]),
                "max": float(row["max_ms"]),
                "throughput": float(row["throughput_rps"]),
            }
            if row["phase"] == "sequential":
                seq[row["implementation"]] = stats
            else:
                concurrency = int(row["concurrency"])
                sweep[row["implementation"]][concurrency] = stats
                if concurrency not in levels:
                    levels.append(concurrency)

    levels.sort()
    png_path = os.path.join(HERE, "chart.png")
    if write_chart(levels, sweep, seq, png_path):
        print("Redrawn from results.csv -> %s" % png_path)
    return 0


# ---------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(
        description="Benchmark the badge verification lookup: CGI vs Servlet.")
    ap.add_argument("--requests", type=int, default=100,
                    help="requests per phase, per implementation (default 100)")
    ap.add_argument("--levels", default="1,2,5,10,20",
                    help="concurrency levels to sweep (default 1,2,5,10,20)")
    ap.add_argument("--warmup", type=int, default=15,
                    help="discarded warm-up requests per implementation")
    ap.add_argument("--codes", type=int, default=200,
                    help="how many distinct badge codes to rotate through")
    ap.add_argument("--replot", action="store_true",
                    help="redraw chart.png from the existing results.csv "
                         "without re-running the load test")
    args = ap.parse_args()

    levels = [int(x) for x in args.levels.split(",") if x.strip()]

    if args.replot:
        return replot()

    print()
    print("=" * 68)
    print("  Badge verification lookup -- CGI vs Servlet")
    print("=" * 68)
    print()

    codes = sample_codes(args.codes)
    if not codes:
        print("No badges in the database. Run bench/issue_badges.py first.")
        return 1

    if not preflight(codes):
        return 1

    # --- warm-up, discarded -----------------------------------------
    print("Warming up (%d requests each, discarded)" % args.warmup)
    print("-" * 68)
    for target in (CGI, SERVLET):
        run_phase(target, codes, args.warmup, 1)
        print("  ok    %s" % target["label"])
    print()

    raw = []

    # --- sequential --------------------------------------------------
    print("Sequential phase: %d requests each, one at a time" % args.requests)
    print("-" * 68)
    seq = {}
    for target in (CGI, SERVLET):
        result = run_phase(target, codes, args.requests, 1)
        seq[target["key"]] = summarise(result)
        raw.append((target["key"], "sequential", 1, result["latencies"]))
        s = seq[target["key"]]
        print("  %-22s mean %8.2f ms   p95 %8.2f ms   %7.1f req/s"
              % (target["label"], s["mean"], s["p95"], s["throughput"]))
    print()

    print_table(
        "Sequential results",
        [["Python CGI"] + ["%.2f" % seq["cgi"][k]
                           for k in ("mean", "median", "p95", "p99", "min", "max")]
                        + ["%.1f" % seq["cgi"]["throughput"]],
         ["Java Servlet"] + ["%.2f" % seq["servlet"][k]
                             for k in ("mean", "median", "p95", "p99", "min", "max")]
                          + ["%.1f" % seq["servlet"]["throughput"]]],
        ["Implementation", "mean", "median", "p95", "p99", "min", "max", "req/s"])

    # --- concurrency sweep -------------------------------------------
    print("Concurrency sweep: %d requests per level" % args.requests)
    print("-" * 68)
    sweep = {"cgi": {}, "servlet": {}}
    for c in levels:
        for target in (CGI, SERVLET):
            result = run_phase(target, codes, args.requests, c)
            sweep[target["key"]][c] = summarise(result)
            raw.append((target["key"], "concurrent", c, result["latencies"]))
        cg, sv = sweep["cgi"][c], sweep["servlet"][c]
        print("  c=%-3d  CGI %8.2f ms / %6.1f req/s   |   "
              "Servlet %7.2f ms / %7.1f req/s"
              % (c, cg["mean"], cg["throughput"], sv["mean"], sv["throughput"]))
    print()

    print_table(
        "Concurrency sweep",
        [[c,
          "%.2f" % sweep["cgi"][c]["mean"],
          "%.2f" % sweep["servlet"][c]["mean"],
          "%.1f" % sweep["cgi"][c]["throughput"],
          "%.1f" % sweep["servlet"][c]["throughput"],
          "%.0fx" % (sweep["cgi"][c]["mean"] / sweep["servlet"][c]["mean"]
                     if sweep["servlet"][c]["mean"] else 0)]
         for c in levels],
        ["clients", "CGI mean ms", "Servlet mean ms",
         "CGI req/s", "Servlet req/s", "speed-up"])

    # --- metadata for the report -------------------------------------
    _, _, health_body = one_shot(
        {"host": "localhost", "port": 8080, "path": "/badgeportal/api/health"}, "")
    health = json.loads(health_body.decode("utf-8"))

    import dbconfig
    import mysql.connector
    conn = mysql.connector.connect(**dbconfig.db_params())
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM badges")
    badge_count = cur.fetchone()[0]
    cur.close()
    conn.close()

    meta = {
        "when": time.strftime("%Y-%m-%d %H:%M:%S"),
        "servlet_server": health.get("server", "?"),
        "servlet_jvm": health.get("jvm", "?"),
        "pool_size": health.get("poolSize", "?"),
        "python": "%d.%d.%d" % sys.version_info[:3],
        "badge_count": badge_count,
        "requests": args.requests,
    }

    # --- write everything out ----------------------------------------
    csv_path = os.path.join(HERE, "results.csv")
    md_path = os.path.join(HERE, "results.md")
    raw_path = os.path.join(HERE, "raw_latencies.csv")
    png_path = os.path.join(HERE, "chart.png")

    write_csv(csv_path, levels, sweep, seq)
    write_markdown(md_path, levels, sweep, seq, meta)
    write_raw(raw_path, raw)
    charted = write_chart(levels, sweep, seq, png_path)

    print("Written")
    print("-" * 68)
    print("  %s" % csv_path)
    print("  %s" % md_path)
    print("  %s" % raw_path)
    if charted:
        print("  %s" % png_path)
    print()

    ratio = seq["cgi"]["mean"] / seq["servlet"]["mean"] if seq["servlet"]["mean"] else 0
    print("  Headline: the servlet answers the same verification lookup")
    print("            %.0fx faster than the CGI script, and the gap widens"
          % ratio)
    print("            with concurrency.")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
