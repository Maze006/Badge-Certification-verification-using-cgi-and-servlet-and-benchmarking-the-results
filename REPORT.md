# Verified Digital Skill-Badge Portal

## Why verification services are built on the Servlet model, not CGI

**PBL 3 — Web Technologies**

---

## 1. What was built

A micro-credentialing portal. Students earn skill badges for completing course
modules; each badge carries a tamper-evident verification code that anyone can
check without an account — the same shape as a Credly or LinkedIn Learning
credential link handed to an employer.

The **verification lookup** is implemented twice:

| | Implementation | Server |
|---|---|---|
| A | Python CGI script | Apache HTTP Server 2.4.58 + `mod_cgi`, port 80 |
| B | Java Servlet | Apache Tomcat 8.5.96, port 8080 |

Both run the same SQL against the same MySQL database as the same database user,
and return byte-identical JSON. Only the request lifecycle differs. That is the
experiment.

### Headline result

> The servlet answers the same verification lookup **269× faster** (1.47 ms vs
> 395.88 ms) and sustains **73× the throughput** (1,790 vs 24.6 requests/second
> at 20 concurrent clients).
>
> Push to 40 concurrent clients and CGI does not merely stop improving — it
> **goes backwards**, halving from 24.6 to 12.7 requests/second while its p95
> response time reaches **4.07 seconds**.
>
> Measurement of the CGI request shows why: **0.13% of it is the database lookup
> the user asked for.** The other 99.87% is setup that the process throws away
> the moment it answers.

---

## 2. Data model

Four tables. `badges` is the one the verification endpoint reads.

| Table | Key fields |
|---|---|
| `students` | `student_id`, `name`, `email`, `enrolled_on` |
| `modules` | `module_id`, `title`, `unit`, `code_prefix` |
| `module_completions` | `student_id`, `module_id`, `score`, `completed_at` |
| `badges` | `badge_id`, `student_id`, `module_id`, `verification_code`, `tier`, `score`, `issued_at_ms`, `issued_at`, `revoked` |

Three design decisions worth defending:

**`module_completions` is not in the original brief but is necessary.** A badge is
evidence that work was completed; without a completion record there is nothing for
issuance to check, and the score that drives the Bronze/Silver/Gold tier has
nowhere to live. `IssueServlet` refuses to mint a badge with no matching
completion row, returning `NOT_COMPLETED`.

**`issued_at_ms BIGINT` sits alongside `issued_at DATETIME(3)`.** The epoch
millisecond value is the canonical one and is what gets hashed. Storing it as a
plain integer means Java and Python derive byte-identical digests with no
timezone conversion or fractional-second rounding between the two runtimes. The
`DATETIME` column is for display and for the schema the brief specifies.

**`verification_code` carries a `UNIQUE` index.** This is what makes the lookup an
index probe rather than a table scan in *both* implementations. Without it the
benchmark would partly be measuring MySQL scanning 336 rows, which would muddy
the comparison it is supposed to make.

Seeded with 60 students, 6 modules and 336 completions, from which 336 badges were
issued.

---

## 3. Verification codes and tamper evidence

A code looks like `JS-3986-8312-B276` — a two-letter module prefix, then twelve hex
digits of a SHA-256 digest in three groups of four.

```
payload = studentId : moduleId : issuedAtMillis : secret
code    = prefix + first 12 hex digits of SHA-256(payload), upper-cased
```

The point is not that the code is unguessable. It is that the code is **derived
from the badge it belongs to**. Because the row stores `student_id`, `module_id`
and `issued_at_ms`, the verifier recomputes the code the row *should* carry and
compares it against the code actually stored. Without the signing secret, nobody
can edit a badge row and keep the two in agreement.

This was tested by altering a badge's `issued_at_ms` by a single millisecond
directly in MySQL:

```
before      HTTP 200  {"status":"VALID",    "valid":true,  ...}
tampered    HTTP 409  {"status":"TAMPERED", "valid":false,
                       "message":"This badge record does not match its own
                                  signature and cannot be trusted."}
restored    HTTP 200  {"status":"VALID",    "valid":true,  ...}
```

Both implementations detected it, because both run the same algorithm —
`BadgeCode.java` and `badgecode.py` are deliberate mirrors of each other.

The status vocabulary shared by both endpoints:

| Status | HTTP | Meaning |
|---|---|---|
| `VALID` | 200 | Genuine and current |
| `NOT_FOUND` | 404 | No badge was ever issued with this code |
| `TAMPERED` | 409 | The row does not match its own signature |
| `REVOKED` | 410 | Issued, then withdrawn |
| `BAD_REQUEST` | 400 | No code supplied |

---

## 4. The two implementations

### What the servlet does per request

1. Tomcat takes a thread from its existing pool.
2. The already-loaded, already-JIT-compiled `VerifyServlet` instance is reused.
3. A live JDBC connection is borrowed from the pool.
4. One indexed `SELECT`, one SHA-256, one JSON write.
5. Connection returns to the pool; thread returns to the pool.

Nothing is created and nothing is destroyed. The expensive work — reading config,
loading the JDBC driver, opening 16 database connections — happens once, in
`AppListener.contextInitialized()`, before the first request arrives.

### What the CGI script does per request

1. Apache forks and execs a **new `python.exe`**.
2. CPython initialises its interpreter and import machinery.
3. `json`, `hashlib`, `datetime`, `urllib.parse` are imported.
4. `mysql.connector` and its whole dependency tree are imported.
5. A fresh TCP connection to MySQL is opened and authenticated.
6. One indexed `SELECT`. ← *the only step that is actual work*
7. The response is written to stdout.
8. **The entire process is torn down** — interpreter, imported modules and
   database connection all discarded.

Steps 1–5 and 8 are pure overhead, paid on every request, and nothing learned
during one request survives into the next. There is no hook in CGI equivalent to
`contextInitialized()`, because a CGI process does not outlive the request that
created it.

---

## 5. Where the time actually goes

Rather than assert that process creation is expensive, each stage was measured
(`bench/cgi_cost_breakdown.py`, median of 10 fresh processes per stage):

| Stage | Cumulative | This step |
|---|---|---|
| 1. Process start (fork/exec + interpreter) | 81.3 ms | 81.27 ms |
| 2. + stdlib imports | 131.4 ms | 50.16 ms |
| 3. + `mysql.connector` import | 324.3 ms | **192.90 ms** |
| 4. + connect and authenticate to MySQL | 368.8 ms | 44.47 ms |
| 5. The indexed `SELECT` | 369.3 ms | **0.49 ms** |

Two things stand out.

**The single most expensive item is importing the database driver — 199 ms**,
nearly half the request. It is also the most obviously wasteful: the same modules
are read, compiled and initialised from scratch for every employer who checks a
badge.

**The work the user actually asked for is 0.49 ms, or 0.13% of the request.** The
remaining 368.8 ms is setup. The servlet pays that 368.8 ms once, at startup, and
then answers each request with the 0.49 ms alone.

That the modelled 369.3 ms lands within 7% of the 395.88 ms measured end-to-end
over HTTP is a good sign the breakdown accounts for the whole story. The residual
is the part this model deliberately leaves out: Apache's own work accepting the
connection, forking the handler and piping the response back.

---

## 6. Benchmark

`bench/benchmark.py`. 100 requests per phase per implementation, preceded by a
discarded warm-up. Codes are drawn at random from the whole `badges` table and
rotated, so neither side benefits from answering one hot row.

Before timing anything, the benchmark asserts that both endpoints return identical
JSON (ignoring `servedBy` and its own `elapsedMs`) and **aborts if they differ** —
if the implementations had drifted apart, the numbers would be comparing different
work.

### Sequential — one client at a time

| Implementation | Mean | Median | p95 | p99 | Min | Max | Throughput |
|---|---|---|---|---|---|---|---|
| Python CGI | 395.88 ms | 396.52 ms | 411.19 ms | 417.59 ms | 292.44 ms | 418.63 ms | 2.5 req/s |
| Java Servlet | **1.47 ms** | 1.19 ms | 2.09 ms | 5.41 ms | 0.83 ms | 20.83 ms | **666.0 req/s** |

### Concurrency sweep

| Concurrent clients | CGI mean | Servlet mean | CGI p95 | Servlet p95 | CGI req/s | Servlet req/s | Speed-up |
|---|---|---|---|---|---|---|---|
| 1 | 394.84 ms | 1.37 ms | 409.99 ms | 1.79 ms | 2.5 | 713.3 | 287× |
| 2 | 395.20 ms | 1.49 ms | 412.82 ms | 1.78 ms | 5.1 | 1308.5 | 266× |
| 5 | 427.43 ms | 1.77 ms | 462.54 ms | 2.18 ms | 11.6 | 2645.3 | 241× |
| 10 | 482.44 ms | 2.67 ms | 593.37 ms | 4.73 ms | 20.1 | 3240.3 | 181× |
| 20 | 763.72 ms | 4.78 ms | 982.56 ms | 11.30 ms | 24.6 | 1789.7 | 160× |
| 40 | **2590.89 ms** | 4.66 ms | **4066.65 ms** | 9.37 ms | **12.7** | 1315.6 | 556× |

![CGI vs Servlet: response time and throughput against concurrency](bench/chart.png)

### Reading the curves

**CGI saturates at about 25 requests/second, then collapses.** Throughput climbs
2.5 → 5.1 → 11.6 → 20.1 → 24.6 as concurrency rises to 20, and then *falls to
12.7* at 40 clients. That is the important row in the table. Doubling the offered
load did not merely fail to buy more work — it **halved the work delivered**,
while mean latency rose from 764 ms to 2,591 ms and p95 reached 4.07 seconds.

Past saturation the machine is spending more of itself creating and destroying
processes than answering with them. Every additional employer checking a badge
makes the service worse for everyone already waiting. A system that degrades
*faster* than the load that caused it is not merely slow, it is unstable: a
modest burst can push it into a state it will not recover from while the burst
lasts.

**The servlet scales until the client runs out of steam.** 713 → 1,308 → 2,645 →
3,240 req/s, with latency still only 2.67 ms at 10 clients. The dips at 20 and 40
concurrent clients are the Python load generator itself becoming the bottleneck,
not the servlet — which means **the servlet figures are conservative and the real
gap is wider than shown**. Its latency over the whole sweep moves only from
1.37 ms to 4.66 ms.

Note that the speed-up *ratio* falls from 287× to 160× across most of the sweep
while the absolute gap *widens* (393 ms → 759 ms). The ratio shrinks only because
the servlet's own latency grows off a very small base; in the terms a user
experiences, the two are pulling further apart, not closer. At 40 clients the
ratio jumps to 556× — not because the servlet improved, but because CGI fell
apart.

---

## 7. Why this matters for real credentialing platforms

A credentialing platform's verification endpoint has a particular load shape:
issuance is rare and bursty, but **verification is public, uncontrolled and
spiky**. A graduating cohort puts a few thousand badge links into CVs; those links
get clicked by employers, recruiters and screening tools, in bursts nobody
schedules.

At 24.6 requests/second, the CGI implementation supports roughly **2.1 million
verifications per day at absolute saturation** — while already making every
employer wait three quarters of a second. Sustained, that is a service running
permanently at its ceiling. The servlet handles the same load at under 1% of
capacity, and its measured 3,240 req/s ceiling is a limit of the test client, not
of the server.

The collapse at 40 clients is the part a platform operator would actually fear.
Bursty public traffic does not politely stop at the saturation point; it
overshoots. And CGI's response to overshoot is not a plateau but a halving of
throughput with multi-second waits — precisely when the most people are looking.

The mechanism behind the difference generalises past this project. Anything a
request handler could usefully keep — a connection pool, a warmed cache, a
compiled query plan, JIT-optimised code, a loaded configuration — CGI is
structurally unable to keep, because its unit of isolation is the process and the
process ends with the request. The servlet container's unit of isolation is the
**thread**, inside one long-lived JVM, so state that is expensive to build is built
once and amortised across every request that follows.

This is why no credentialing platform operating at scale runs verification through
CGI, and it is the same reasoning that later produced FastCGI, `mod_php`, WSGI
application servers and every modern application-server model: they all exist to
stop paying process-creation costs on the request path.

To be fair to CGI: it is genuinely simpler, it isolates faults completely (a
crashing script cannot take down its neighbours), it needs no application server,
and for a low-traffic form handler none of the above matters. The lesson is not
that CGI is bad — it is that the model stops fitting the moment request volume
rises, and verification lookups are exactly that case.

---

## 8. Deployment

Full step-by-step instructions are in [README.md](README.md). In outline:

```bash
mysql -u root -p < sql/01_schema.sql
```

```bash
mysql -u root -p < sql/02_seed.sql
```

```bash
scripts\deploy.bat
```

```bash
scripts\start_tomcat.bat
```

```bash
python bench\issue_badges.py
```

```bash
python bench\benchmark.py
```

`deploy.bat` compiles the servlets, assembles an exploded web application into
`C:\xampp\tomcat\webapps\badgeportal\`, and installs the CGI script into
`C:\xampp\cgi-bin\` with its shebang rewritten to the local `python.exe`. No
`httpd.conf` changes are required: `C:\xampp\cgi-bin` is already `ScriptAlias`-ed,
and a `ScriptAlias` directory executes every file in it regardless of extension.

Servlet mappings are declared explicitly in `web.xml` rather than by `@WebServlet`
annotation, so the whole URL surface is readable in one place:

| Servlet | Mapping |
|---|---|
| `VerifyServlet` | `/api/verify` |
| `IssueServlet` | `/api/issue` |
| `WalletServlet` | `/wallet` |
| `HealthServlet` | `/api/health` |

Three environment-specific problems were hit and are documented with their causes
and fixes in the README: Tomcat must run on the Java 8 runtime because
`Selector.open()` fails under Java 25 on this machine; the MySQL driver must be
vendored beside the CGI script because Apache does not pass `APPDATA` to CGI
processes; and the CGI shebang needs the 8.3 short path because
`C:\Program Files\…` contains a space.

---

## 9. Modules delivered

| Module | Where | Notes |
|---|---|---|
| Badge issuance | `IssueServlet` | Idempotent; checks completion; assigns tier from score |
| Verification lookup (CGI) | `cgi/verify.py` | Benchmarked |
| Verification lookup (Servlet) | `VerifyServlet` | Benchmarked |
| Student wallet | `WalletServlet` | Badges with codes, copy buttons, verify links; issues unclaimed badges |
| Public verify page | `verify.html` | Employer-facing; code only; **switches between the two implementations and shows the round-trip time** |
| Benchmark | `bench/benchmark.py` | CSV, markdown, chart, raw samples |
| Cost breakdown | `bench/cgi_cost_breakdown.py` | Stage-by-stage CGI timing |

Both stretch goals are included: the public verify page, and Bronze/Silver/Gold
tiers assigned from module score (≥90 Gold, ≥75 Silver, otherwise Bronze).

The verify page is worth singling out as a teaching artefact: it answers the same
lookup through either implementation on a toggle and prints the round-trip time,
so the difference this report measures can be watched happening live — typically
around 2 ms against the servlet and 350 ms against CGI.

---

## 10. Limitations

Stated plainly, because they bound what the numbers support.

- **The client is the servlet's ceiling.** The Python load generator saturates
  before the servlet does, so 3,240 req/s is a floor on servlet capacity, not a
  measurement of it. A JMeter or `wrk` run would show a larger gap.
- **Everything is on one machine.** Client, both servers and MySQL share a CPU, so
  they compete. Real deployments separate them.
- **CGI in Python is not the fastest possible CGI.** A compiled C CGI binary would
  avoid the 193 ms interpreter-import cost and land far closer to the servlet.
  What survives that objection is the process-creation floor: 81.3 ms per request
  on this machine, still 55× the servlet's total response time, and unavoidable
  in any CGI implementation in any language.
- **The concurrency sweep stops at 40 clients** to avoid thrashing the machine
  with concurrent `python.exe` processes. CGI has already collapsed by then, so
  higher levels would only widen the gap.
- **Run-to-run variance is real.** Repeated runs of this benchmark put the
  sequential speed-up between roughly 140× and 290×, because both servers, the
  client and MySQL share one desktop CPU alongside whatever else is running.
  Every figure quoted in this report comes from a single run, recorded in
  `bench/results.csv`, rather than being assembled from several.
- **Codes are 48 bits of digest.** Ample against collision for a project of this
  size and enforced by a unique index, but a production system would use a longer
  code and rotate the signing secret.

---

## 11. Conclusion

The two implementations answer the same question with the same SQL against the
same database and return the same bytes. The servlet does it 269× faster and
absorbs 73× the concurrent load — and where CGI collapses under overshoot, the
servlet does not notice.

The measured cost breakdown explains the whole difference without hand-waving:
**0.13% of a CGI verification request is the lookup; 99.87% is rebuilding context
that the previous request already had and threw away.** The servlet builds that
context once, when the application starts, and every request afterwards is very
nearly just the work.

For a public verification endpoint — the piece of a credentialing platform that
strangers hit hardest and least predictably — that is the difference between a
service that scales and one that is already at its ceiling.
