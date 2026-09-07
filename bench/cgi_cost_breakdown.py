"""
Where does a CGI request actually spend its time?

The benchmark shows that the CGI lookup costs ~400 ms and the servlet
~1.7 ms. Saying "process creation is expensive" explains the shape of
that but not the size of it, so this script takes the CGI request apart
and times each stage separately, by launching a real python.exe the way
Apache does and having it report timestamps.

    python bench/cgi_cost_breakdown.py

Stages measured, cumulatively:

    1. process start      fork/exec + CPython interpreter startup
    2. + stdlib imports   json, hashlib, datetime, urllib.parse
    3. + driver import    mysql.connector and everything it pulls in
    4. + DB connect       TCP connect and the MySQL auth handshake

Every one of those is overhead that the servlet pays once at startup and
the CGI script pays on every single request.

The query itself -- the only actual work -- is timed SEPARATELY, from
inside the process with perf_counter. Timing it by subprocess
differencing the way the stages above are timed does not work: an
indexed lookup on this table takes well under a millisecond, which is
far below the run-to-run variance of launching a Windows process, so the
subtraction returns noise and can even come out negative. Measuring it
in-process is both more accurate and more honest about what is being
compared.
"""

import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
CGI_DIR = os.path.join(PROJECT, "cgi")

RUNS = 10

# Each probe is a complete program run in a fresh interpreter. The
# parent times the whole subprocess, so "process start" includes the
# process creation Apache would do -- the thing a CGI script can never
# avoid and a servlet never repeats.
PROBES = [
    ("1. process start (fork/exec + interpreter)", "pass"),
    ("2. + stdlib imports", "import json, hashlib, datetime, urllib.parse"),
    ("3. + mysql.connector import",
     "import json, hashlib, datetime, urllib.parse; import mysql.connector"),
    ("4. + connect to MySQL",
     "import json, hashlib, datetime, urllib.parse; import mysql.connector, dbconfig;"
     " c = mysql.connector.connect(**dbconfig.db_params()); c.close()"),
]

# Timed inside the child with perf_counter, then printed, because it is
# far too fast to measure by differencing process wall times.
QUERY_PROBE = """
import time, mysql.connector, dbconfig
SQL = ('SELECT b.badge_id, s.name, m.title FROM badges b '
       'JOIN students s ON s.student_id=b.student_id '
       'JOIN modules m ON m.module_id=b.module_id '
       'WHERE b.verification_code=%s')
c = mysql.connector.connect(**dbconfig.db_params())
cur = c.cursor()
cur.execute(SQL, (CODE,))   # first execution primes the server-side caches
cur.fetchone()
samples = []
for _ in range(50):
    t0 = time.perf_counter()
    cur.execute(SQL, (CODE,))
    cur.fetchone()
    samples.append((time.perf_counter() - t0) * 1000.0)
cur.close(); c.close()
samples.sort()
print('%.4f' % samples[len(samples) // 2])
"""


def one_code():
    sys.path.insert(0, CGI_DIR)
    import dbconfig
    import mysql.connector
    conn = mysql.connector.connect(**dbconfig.db_params())
    cur = conn.cursor()
    cur.execute("SELECT verification_code FROM badges LIMIT 1")
    row = cur.fetchone()
    cur.close()
    conn.close()
    return row[0] if row else None


def child_env():
    env = dict(os.environ)
    env["PYTHONPATH"] = CGI_DIR + os.pathsep + os.path.join(CGI_DIR, "pylib")
    return env


def time_probe(body, code):
    """Median wall time of running `body` in a fresh interpreter."""
    program = "CODE = %r\n%s\n" % (code, body)
    samples = []
    for _ in range(RUNS):
        started = time.perf_counter()
        subprocess.run([sys.executable, "-c", program],
                       env=child_env(), check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        samples.append((time.perf_counter() - started) * 1000.0)
    samples.sort()
    return samples[len(samples) // 2]


def time_query(code):
    """Median of the SELECT alone, measured inside the child process."""
    program = "CODE = %r\n%s\n" % (code, QUERY_PROBE)
    out = subprocess.run([sys.executable, "-c", program],
                         env=child_env(), check=True,
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    return float(out.stdout.decode().strip())


def main():
    code = one_code()
    if code is None:
        print("No badges in the database. Run bench/issue_badges.py first.")
        return 1

    print()
    print("=" * 70)
    print("  Where a CGI request spends its time")
    print("  (median of %d fresh processes per stage)" % RUNS)
    print("=" * 70)
    print()

    previous = 0.0
    rows = []
    for label, body in PROBES:
        total = time_probe(body, code)
        rows.append((label, total, total - previous))
        previous = total

    setup = rows[-1][1]
    query_ms = time_query(code)
    rows.append(("5. the indexed SELECT (the only real work)",
                 setup + query_ms, query_ms))

    width = max(len(r[0]) for r in rows)
    print("  %-*s  %10s  %10s" % (width, "Stage", "cumulative", "this step"))
    print("  %s  %s  %s" % ("-" * width, "-" * 10, "-" * 10))
    for label, total, step in rows:
        print("  %-*s  %8.1f ms  %9.2f ms" % (width, label, total, step))
    print()

    total_ms = setup + query_ms
    print("  Of %.1f ms, the database lookup the user actually asked for"
          % total_ms)
    print("  is %.2f ms -- %.2f%% of the request." % (query_ms,
                                                     100.0 * query_ms / total_ms))
    print("  The other %.1f ms is setup that the CGI process throws away the"
          % setup)
    print("  moment it answers, and rebuilds from nothing on the next request.")
    print()
    print("  The servlet pays that %.1f ms ONCE, at application startup, and" % setup)
    print("  then answers every later request with the %.2f ms alone." % query_ms)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
