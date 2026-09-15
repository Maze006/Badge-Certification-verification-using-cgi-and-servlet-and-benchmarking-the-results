# How this project works, and why

A walkthrough of the Verified Digital Skill-Badge Portal: what was built, how
each piece works, why it was built that way, and — in detail — what CGI and
servlets actually are and why they perform so differently.

This is the explanatory companion to the other two documents:

| Document | Answers |
|---|---|
| `README.md` | How do I set it up and run it? |
| `REPORT.md` | What are the results and what do they prove? |
| `docs/EXPLAINED.md` (this file) | How does it work, and why is it like this? |

---

## Contents

1. [What the project is](#1-what-the-project-is)
2. [What was built](#2-what-was-built)
3. [How a badge is issued](#3-how-a-badge-is-issued)
4. [How verification works](#4-how-verification-works)
5. [What CGI actually is](#5-what-cgi-actually-is)
6. [What a servlet actually is](#6-what-a-servlet-actually-is)
7. [Why they differ, in detail](#7-why-they-differ-in-detail)
8. [The evidence](#8-the-evidence)
9. [When CGI is still the right choice](#9-when-cgi-is-still-the-right-choice)
10. [Glossary](#10-glossary)

---

## 1. What the project is

Students earn micro-credential badges for completing course modules. Each badge
gets a verification code that an employer can check — no account, no login, just
the code. That is exactly how Credly, Coursera and LinkedIn Learning badges work
in practice.

The project builds the **verification lookup twice**:

- once as a **Python CGI script** running under Apache HTTP Server
- once as a **Java servlet** running under Apache Tomcat

Both do identical work and return identical JSON. Then they are benchmarked
against each other, and the difference is explained.

**Why this shape?** The syllabus requires covering CGI. Rather than treat CGI as
a historical curiosity, the project puts it head-to-head with the servlet model
on a task where the difference actually matters — a public endpoint that
strangers hit unpredictably.

---

## 2. What was built

### The database

Four tables in MySQL: `students`, `modules`, `module_completions`, `badges`.
See `docs/schema.svg` for the diagram and `README.md` for column-by-column
detail.

**Why `module_completions` exists** even though the brief did not ask for it: a
badge is *evidence that work was done*. Without a completion record there is
nothing for issuance to check, and the score that determines the tier has
nowhere to live. `IssueServlet` refuses to mint a badge with no matching
completion.

**Why `badges` stores `score` again**, duplicating the completion: a credential
should record what was true at the moment it was issued. If a lecturer later
corrects a score, an already-issued badge must not silently change meaning.

**Why `issued_at_ms BIGINT` sits beside `issued_at DATETIME(3)`**: the
millisecond integer is the value fed into the hash. Storing it as a plain
integer means Java and Python produce byte-identical digests, with no timezone
conversion or fractional-second rounding drifting between the two runtimes.
The `DATETIME` is for humans.

### The application

| Component | Language | Job |
|---|---|---|
| `VerifyServlet` | Java | The benchmarked lookup |
| `verify.py` | Python | The benchmarked lookup, again |
| `IssueServlet` | Java | Mints badges |
| `WalletServlet` | Java | The student-facing page |
| `HealthServlet` | Java | Connection-pool statistics |
| `Db` | Java | The JDBC connection pool |
| `AppListener` | Java | Opens the pool once, at startup |
| `BadgeCode` / `badgecode.py` | Both | The code algorithm, mirrored |

**Why is issuance only a servlet?** The PBL asks for the *verification lookup*
to exist twice, not the whole application. That also matches how real
credentialing platforms are shaped: issuance is a low-volume, transactional,
authenticated path; verification is the high-volume public one. You optimise
the path that takes the traffic.

### The measurement tools

- `bench/benchmark.py` — the load test, sequential and concurrent
- `bench/cgi_cost_breakdown.py` — takes one CGI request apart, stage by stage
- `bench/issue_badges.py` — populates the database through the real endpoint

**Why issue badges over HTTP instead of inserting rows directly?** Because then
the codes being verified were genuinely produced by the issuance code path. A
SQL fixture would prove nothing about whether issuance works.

---

## 3. How a badge is issued

`POST /badgeportal/api/issue` with `studentId` and `moduleId`.

1. **Check the completion.** No row in `module_completions`? Return
   `NOT_COMPLETED`. You cannot be credentialed for work you did not do.
2. **Check for an existing badge.** Already issued? Return the existing one with
   `ALREADY_ISSUED`. Issuance is idempotent — clicking twice must not mint two
   codes.
3. **Decide the tier** from the score: ≥90 Gold, ≥75 Silver, otherwise Bronze.
4. **Take a timestamp** in epoch milliseconds. This becomes `issued_at_ms`.
5. **Generate the code** (below).
6. **Insert.** A unique index on `(student_id, module_id)` enforces
   idempotency at the database level too, so two simultaneous requests cannot
   both succeed.

### The verification code

```
payload = studentId : moduleId : issuedAtMillis : secret
digest  = SHA-256(payload)
code    = modulePrefix + "-" + first 12 hex digits of digest, in groups of four
```

Producing something like `SF-DDDF-2864-66C7`.

**Why a hash rather than a random string?** A random code would need only a
lookup to verify — and would be perfectly valid even if someone edited the row
it points at. A *derived* code is bound to the badge's own data.

### Why this is "tamper-evident"

Because the badge row stores `student_id`, `module_id` and `issued_at_ms`, the
verifier can **recompute** the code the row *should* carry and compare it with
the code actually stored. Without the signing secret, nobody can edit a badge
row and keep those two in agreement.

Demonstrated by changing one badge's `issued_at_ms` by a single millisecond
directly in MySQL:

```
before      HTTP 200  {"status":"VALID",    "valid":true  ...}
tampered    HTTP 409  {"status":"TAMPERED", "valid":false ...}
restored    HTTP 200  {"status":"VALID",    "valid":true  ...}
```

Both implementations caught it, because both run the same algorithm.

Note carefully what this does and does not claim. It does **not** prevent
database edits. It makes them **detectable**. That is the honest security
property, and it is worth stating precisely rather than overclaiming.

---

## 4. How verification works

`GET /api/verify?code=XXX` or `GET /cgi-bin/verify.py?code=XXX`

Both implementations do exactly this:

1. Normalise the code (trim, upper-case, tolerate missing dashes)
2. One indexed `SELECT` joining `badges`, `students` and `modules`
3. Recompute the expected code from the row; mismatch → `TAMPERED`
4. Check the `revoked` flag
5. Emit JSON

The status vocabulary, shared by both:

| Status | HTTP | Meaning |
|---|---|---|
| `VALID` | 200 | Genuine and current |
| `NOT_FOUND` | 404 | No badge was ever issued with this code |
| `TAMPERED` | 409 | The row does not match its own signature |
| `REVOKED` | 410 | Issued, then withdrawn |
| `BAD_REQUEST` | 400 | No code supplied |

The responses are identical down to key order — the benchmark asserts it and
aborts if they ever diverge.

---

## 5. What CGI actually is

### The definition

**CGI — the Common Gateway Interface — is not a language.** It is a *convention*
from 1993 for how a web server hands a request to an external program and gets a
response back. You can write a CGI script in Python, Perl, C, Bash, anything
that can read environment variables and write to standard output.

The convention has three parts.

**1. The server passes request data in environment variables.**

When Apache receives `GET /cgi-bin/verify.py?code=SF-1234`, it sets:

```
REQUEST_METHOD   = GET
QUERY_STRING     = code=SF-1234
SCRIPT_NAME      = /cgi-bin/verify.py
SERVER_PROTOCOL  = HTTP/1.1
REMOTE_ADDR      = 127.0.0.1
CONTENT_LENGTH   = (for POST)
```

This is why `verify.py` reads `os.environ.get("QUERY_STRING")`. There is no
framework and no request object — just environment variables.

**2. POST bodies arrive on standard input.** Not used here, since verification
is a GET.

**3. The script writes headers, a blank line, then the body — to stdout.**

```
Status: 200 OK
Content-Type: application/json;charset=UTF-8
Content-Length: 357
<blank line>
{"code":"SF-1234","valid":true,...}
```

Apache reads that from the process's stdout, turns it into a real HTTP response,
and sends it to the browser. The blank line separating headers from body is the
entire protocol.

### The execution model — the part that matters

**Apache runs the script as a brand-new operating-system process, one per
request.**

For every single request:

1. `fork`/`exec` — the OS creates a new process
2. CPython initialises: the interpreter, the import machinery, built-ins
3. The script's imports run — `json`, `hashlib`, `datetime`, then the whole
   `mysql.connector` package
4. A fresh TCP connection to MySQL is opened and authenticated
5. The query runs ← *the only actual work*
6. The response is written to stdout
7. **The process exits.** Interpreter, imported modules, database connection —
   all destroyed

Then the next request starts again at step 1, having learned nothing.

### Why it was designed this way

CGI is from an era when a "web application" meant a form that emailed you. Its
design goals were **simplicity and isolation**, and it achieves both
brilliantly:

- Any language works. If it can print, it can serve.
- A crashing script cannot take down the web server or affect any other request
   — the blast radius is one process.
- No application server to install, configure or keep running.
- No shared state means no shared-state bugs.

Those are real virtues. The cost is that a process cannot outlive the request
that created it, so **nothing can be kept between requests.**

---

## 6. What a servlet actually is

### The definition

**A servlet is a Java class that implements the `Servlet` interface** — in
practice, one that extends `HttpServlet` and overrides `doGet` or `doPost`. It
does not run on its own. It runs inside a **servlet container** (here, Apache
Tomcat), which is a long-lived Java program that owns the network sockets, the
threads, and the servlet's whole life.

You write the method that answers a request. The container does everything else.

### The lifecycle

The container calls three methods, at three different frequencies — and the
difference between them is the entire performance story:

| Method | Called | In this project |
|---|---|---|
| `init()` | **Once**, when the app starts | Logs; the pool is opened by `AppListener` |
| `service()` → `doGet()` | **Every request** | The lookup |
| `destroy()` | **Once**, at shutdown | Pool closed by `AppListener` |

Expensive setup goes in the once-per-application slot. The per-request method
does as little as possible. That is the whole design idea.

`web.xml` says `<load-on-startup>1</load-on-startup>` on `VerifyServlet`, which
tells Tomcat to instantiate it at boot rather than lazily on first request — so
the very first *timed* request is not paying class-loading costs.

### The threading model

**This is the crucial point, and it is where most people's mental model is
wrong.**

Tomcat creates **one instance** of your servlet class and shares it across
**every concurrent request**. It does not create a servlet per request. It keeps
a pool of worker threads, and each incoming request is handed to a free thread,
which calls `doGet()` on that same single shared instance.

Two consequences follow, and they are two sides of the same coin.

**The good side:** anything expensive can be built once and reused by every
request forever after — a connection pool, a cache, compiled query plans, and
the JIT-optimised machine code the JVM generates for hot methods.

**The dangerous side:** because one instance is shared across threads, **servlet
instance fields are shared mutable state.** Writing a request's data to a field
is a genuine bug that appears only under concurrency — one user seeing another
user's data. This is why `VerifyServlet` keeps everything in local variables
inside `doGet()`, and why the only shared object is `Db`, which is explicitly
built to be thread-safe.

### The connection pool

`AppListener.contextInitialized()` runs once, when Tomcat starts the
application, and does the expensive work up front:

- reads `badgeportal.properties`
- loads the MySQL JDBC driver
- opens **16 database connections** and parks them in a queue

A request then borrows a live connection, uses it for well under a millisecond,
and hands it straight back. It never opens one.

**This is the single biggest reason for the performance gap, and CGI cannot do
it** — not because of a missing library, but because there is nowhere for a pool
to live between two processes.

---

## 7. Why they differ, in detail

### The mechanism

| | CGI | Servlet |
|---|---|---|
| Unit of isolation | **Process** | **Thread** |
| Created per request | A whole OS process | Nothing — a thread is borrowed |
| Lives for | One request | The life of the server |
| Code loading | Re-imported every request | Loaded once |
| DB connection | Opened and closed every request | Borrowed from a live pool |
| Optimisation | None — every run is the first run | JIT compiles hot paths to machine code |
| Memory | New address space each time | One shared heap |
| Shared-state bugs | Impossible | Possible — you must write thread-safe code |
| Crash blast radius | One request | Potentially the whole application |

### Where the time actually goes

Measured, not assumed (`bench/cgi_cost_breakdown.py`, median of 10 fresh
processes per stage):

| Stage | This step | Servlet equivalent |
|---|---|---|
| Process start (fork/exec + interpreter) | 81.27 ms | none |
| stdlib imports | 50.16 ms | paid once at startup |
| `mysql.connector` import | **192.90 ms** | paid once at startup |
| Connect and authenticate to MySQL | 44.47 ms | paid once at startup |
| **The indexed SELECT** | **0.49 ms** | **0.49 ms** |

**0.49 ms of a 369 ms request is the work the user asked for. That is 0.13%.**

The other 368.8 ms is context that the previous request already had and threw
away on exit. The servlet pays that same 368.8 ms exactly once, when the
application starts, and then answers every subsequent request with the 0.49 ms
alone.

That single table is the whole argument. Everything else is confirmation.

### An analogy that holds up

The servlet is a shop that opens in the morning: unlock once, boot the tills
once, then serve customers all day.

CGI is a shop that, for **every single customer**, unlocks the doors, boots the
tills, phones the warehouse to establish an account, sells one item, then shuts
down completely — and does the whole thing again for the next person.

Neither shop sells items faster than the other. One just spends 99.9% of its
time on setup.

### Why the gap widens under load

At one client at a time, CGI is slow but steady — each request pays its own
fixed setup cost with nothing contending.

Add concurrency and a second effect appears: the machine must now create many
processes at once, and process creation is expensive for the *operating system*,
not just for the requester. Measured:

| Clients | CGI latency | CGI throughput |
|---|---|---|
| 1 | 395 ms | 2.5 req/s |
| 5 | 427 ms | 11.6 req/s |
| 10 | 482 ms | 20.1 req/s |
| 20 | 764 ms | 24.6 req/s |
| 40 | **2,591 ms** | **12.7 req/s** ← *halved* |

Look at the last row. Doubling the offered load **more than tripled the wait and
halved the work delivered**. That is saturation turning into collapse: past a
certain point the machine spends more of itself creating and destroying
processes than answering with them, so adding load actively subtracts capacity.
At that level CGI's p95 is **4.07 seconds** — one request in twenty takes longer
than most people will wait.

This is worse than being slow. A system that degrades *faster* than the load
causing it cannot absorb a burst; it amplifies one.

The servlet at 40 concurrent clients: **4.66 ms**, 1,316 req/s — essentially
unchanged from 20 clients, because threads are cheap and the pool absorbs the
load. Its latency across the entire sweep moves only from 1.37 ms to 4.66 ms.

### Why this matters for a credentialing platform

Verification traffic is public, uncontrolled and spiky. A graduating cohort puts
thousands of badge links into CVs; those get clicked by employers and screening
tools in bursts nobody schedules.

At a 24.6 req/s ceiling, the CGI version supports about 2.1 million verifications
per day *at absolute saturation*, while already making every employer wait over
a second. The servlet handles the same load at under 1% of capacity.

And the mechanism generalises far past this project. Anything a request handler
could usefully keep — a pool, a cache, a compiled plan, warmed-up code, loaded
config — CGI structurally cannot keep. This is precisely why FastCGI, `mod_php`,
WSGI application servers and every modern application-server model were
invented: **all of them exist to stop paying process-creation costs on the
request path.**

---

## 8. The evidence

### Results

| | CGI | Servlet | |
|---|---|---|---|
| Mean response | 395.88 ms | **1.47 ms** | 269x |
| Median | 396.52 ms | 1.19 ms | |
| p95 | 411.19 ms | 2.09 ms | |
| Throughput ceiling | 24.6 req/s | 3,240 req/s | 132x |

### Why the comparison is fair

A speed comparison is only worth something if both sides do equal work. Held
constant on purpose:

- **Same SQL**, same MySQL instance, same database user (`badgeuser`), same
  unique-indexed column
- **Identical responses** — asserted before timing starts, on five sampled
  codes, ignoring only the two fields that name the implementation and report
  its own timing. If they differ, the benchmark **aborts** rather than produce
  numbers
- **Same client, same connection-reuse policy** for both, so client-side TCP
  setup is out of the measurement on both sides
- **Rotating codes** drawn at random from all 336 badges, so neither side gets
  to answer one hot row repeatedly
- **A discarded warm-up phase**, so the servlet is past class-loading and JIT
  warm-up before anything is recorded

That abort-on-mismatch check is the answer if someone asks *"how do you know the
CGI version wasn't just doing less work?"* If it were, the run would not have
produced numbers at all.

### What the numbers do not prove

Stated plainly, because knowing the limits of your own evidence is the point:

- **The servlet's ceiling is the test client, not the servlet.** The load
  generator is Python on the same machine. 3,240 req/s is a floor on servlet
  capacity. The honest claim is "at least 132x".
- **Everything shares one CPU** — client, both servers, MySQL.
- **This is not the fastest possible CGI.** A compiled C CGI binary would skip
  the 199 ms interpreter-import cost entirely. What survives that objection is
  the **81.27 ms process-creation floor** - still 55x the servlet's *entire*
  response time, and unavoidable in any CGI, in any language, on this machine.

---

## 9. When CGI is still the right choice

The lesson is not "CGI is bad". It is "the model stops fitting when request
volume rises". CGI remains genuinely reasonable when:

- Traffic is low — an internal form handler, an admin script, a webhook that
  fires a few times a day
- **Isolation matters more than speed.** A crashing CGI script cannot corrupt a
  neighbouring request, because they share no memory at all. A servlet with a
  thread-safety bug absolutely can.
- You want zero infrastructure. No application server, no deployment descriptor,
  no container to keep running.
- The script is genuinely one-shot, and per-request state would be a liability.

The engineering judgement is about matching the model to the traffic — not about
one technology being universally better.

---

## 10. Glossary

**CGI (Common Gateway Interface)** — a 1993 convention for a web server to run
an external program per request, passing input via environment variables and
reading the response from the program's stdout. Not a language.

**Servlet** — a Java class that handles HTTP requests inside a servlet
container. One instance is shared across many concurrent request threads.

**Servlet container** — the long-running Java program (Tomcat) that owns the
sockets and threads and manages servlet lifecycles. Also called an application
server.

**Connection pool** — a set of database connections opened once and reused
across requests, avoiding the TCP connect and authentication handshake every
time. Requires a process that outlives the request, which is exactly what CGI
lacks.

**JIT (Just-In-Time compilation)** — the JVM compiles frequently executed
bytecode into native machine code while running. Requires a long-lived process
to be worth anything; a CGI process dies long before it could benefit.

**Idempotent** — an operation that gives the same result whether performed once
or many times. Badge issuance is idempotent: asking twice returns the same
badge, not two.

**p95 / p99** — the response time that 95% (or 99%) of requests came in under.
More honest than an average, because averages hide the slow tail that users
actually complain about.

**Throughput saturation** — the point where adding load stops increasing work
done and only increases waiting. CGI reaches it at about 25 requests/second on
this machine.

**Tamper-evident** — edits can be *detected*, not prevented. A badge row that no
longer matches the digest recomputed from its own columns is rejected.
