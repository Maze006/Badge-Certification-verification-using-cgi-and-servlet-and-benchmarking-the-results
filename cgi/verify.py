#!C:/PROGRA~1/Python313/python.exe
"""
THE BENCHMARKED ENDPOINT (CGI half).

    GET http://localhost/cgi-bin/verify.py?code=SF-3A9F-B21C-7E04

servlet/src/com/badgeportal/VerifyServlet.java is the other half. The
two are held to the same contract -- same query parameter, same SQL,
same JSON keys, same status vocabulary -- so that the only difference
the benchmark can possibly be measuring is the request lifecycle.

WHAT APACHE HAS TO DO FOR EVERY SINGLE REQUEST TO THIS FILE
-----------------------------------------------------------
  1. fork/exec a brand-new python.exe process
  2. initialise the CPython interpreter and its import machinery
  3. import hashlib, json, decimal, and the whole mysql.connector stack
     -- tens of modules, compiled or read from disk each time
  4. open a fresh TCP connection to MySQL and complete the
     authentication handshake
  5. run one indexed SELECT  <-- the only step that is actual work
  6. serialise the answer, write it to stdout
  7. close the connection and TEAR THE ENTIRE PROCESS DOWN, discarding
     the interpreter, the imported modules and the database connection

Steps 1-4 and 7 are pure overhead, they are paid on every request, and
NOTHING learned during one request survives into the next. The servlet
does steps 1-4 exactly once, at application startup, and then answers
every subsequent request with step 5 alone.

That is the entire argument of this project, and the shape of it is
visible in the numbers bench/benchmark.py produces.

NOTE: the standard-library `cgi` module was removed in Python 3.13, so
the query string is parsed directly with urllib.parse -- which is what
that module did anyway.
"""

import json
import os
import sys
import time
from datetime import datetime, timezone
from urllib.parse import parse_qs

# The interpreter is brand new, so every import below is paid for again
# on every single request.
_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)

# mod_cgi hands the script a deliberately minimal environment: on
# Windows it passes SystemRoot, PATH and COMSPEC but NOT APPDATA, and
# without APPDATA the interpreter cannot locate the per-user
# site-packages directory. A driver installed with a plain "pip install"
# (which falls back to a user install when Program Files is not
# writable) is therefore invisible to the CGI process even though it
# imports perfectly well from a normal shell.
#
# So the driver is vendored into pylib/ next to this script by
# scripts/deploy.bat, which makes the import work no matter which
# account Apache happens to be running as -- including the SYSTEM
# account it uses when started as a Windows service from the XAMPP
# control panel.
sys.path.insert(0, os.path.join(_HERE, "pylib"))

_T0 = time.perf_counter()

# LEFT JOIN on modules and badge_claims, not INNER. A badge issued from
# an approved claim has no module_id, so an inner join would fail to
# match it and this endpoint would answer NOT_FOUND for a badge that
# genuinely exists.
#
# This query and everything built from it below must stay identical to
# VerifyServlet.java. bench/benchmark.py samples codes at random and
# aborts if the two implementations disagree, so any drift here stops
# the benchmark rather than quietly skewing it.
SQL = (
    "SELECT b.badge_id, b.student_id, b.module_id, b.verification_code, "
    "       b.tier, b.score, b.issued_at_ms, b.revoked, "
    "       s.name AS student_name, s.email AS student_email, "
    "       m.title AS module_title, m.unit AS module_unit, m.code_prefix, "
    "       c.title AS claim_title, c.issuer AS claim_issuer "
    "FROM badges b "
    "JOIN      students     s ON s.student_id = b.student_id "
    "LEFT JOIN modules      m ON m.module_id  = b.module_id "
    "LEFT JOIN badge_claims c ON c.claim_id   = b.claim_id "
    "WHERE b.verification_code = %s"
)

STATUS_LINE = {
    200: "200 OK",
    400: "400 Bad Request",
    404: "404 Not Found",
    409: "409 Conflict",
    410: "410 Gone",
    500: "500 Internal Server Error",
}


def elapsed_ms():
    """Server-side time, mirroring the servlet field of the same name."""
    return round((time.perf_counter() - _T0) * 1000.0, 3)


def utc(epoch_millis):
    """
    "yyyy-MM-dd HH:mm:ss" in UTC -- identical to TimeFmt.utc() on the
    Java side. Integer-divided to whole seconds so that both runtimes
    truncate the same way rather than one of them rounding.
    """
    return datetime.fromtimestamp(epoch_millis // 1000, timezone.utc) \
                   .strftime("%Y-%m-%d %H:%M:%S")


def respond(http_status, payload):
    """
    Writes the CGI response: headers, a blank line, then the body.

    Written to the raw byte stream rather than to sys.stdout, because on
    Windows the text stream would translate every \\n into \\r\\n and
    corrupt the body.
    """
    payload["servedBy"] = "cgi"
    payload["elapsedMs"] = elapsed_ms()

    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    headers = (
        "Status: %s\r\n"
        "Content-Type: application/json;charset=UTF-8\r\n"
        "Content-Length: %d\r\n"
        "Cache-Control: no-store\r\n"
        "Access-Control-Allow-Origin: *\r\n"
        "\r\n" % (STATUS_LINE.get(http_status, "200 OK"), len(body))
    ).encode("ascii")

    out = sys.stdout.buffer
    out.write(headers)
    out.write(body)
    out.flush()


def read_code():
    """Pulls ?code=... out of QUERY_STRING, the way CGI hands it over."""
    qs = os.environ.get("QUERY_STRING", "")
    values = parse_qs(qs, keep_blank_values=True).get("code", [""])
    return values[0]


def main():
    import badgecode
    import dbconfig

    code = badgecode.normalise(read_code())

    if not code:
        respond(400, {
            "code": "",
            "valid": False,
            "status": "BAD_REQUEST",
            "message": "Missing required query parameter: code",
        })
        return

    # A fresh connection, every request. There is no pool to borrow from:
    # the process that opened the last one no longer exists.
    import mysql.connector

    conn = None
    try:
        conn = mysql.connector.connect(**dbconfig.db_params())
        cur = conn.cursor(dictionary=True)
        cur.execute(SQL, (code,))
        row = cur.fetchone()
        cur.close()

        if row is None:
            respond(404, {
                "code": code,
                "valid": False,
                "status": "NOT_FOUND",
                "message": "No badge has ever been issued with this code.",
            })
            return

        student_id = int(row["student_id"])
        issued_at_ms = int(row["issued_at_ms"])
        stored = row["verification_code"]

        # A claim badge has no module and no module prefix; both fall
        # back to the shared constants the issuer used.
        module_id = (badgecode.NO_MODULE if row["module_id"] is None
                     else int(row["module_id"]))
        prefix = row["code_prefix"] or badgecode.CLAIM_PREFIX

        # ---- the tamper-evident check ------------------------------
        # Recompute the code this badge row SHOULD carry, from the row
        # itself plus the shared signing secret. Someone who edits the
        # database to point a valid-looking code at a different student
        # cannot produce a matching digest without the secret.
        expected = badgecode.generate(
            prefix, student_id, module_id, issued_at_ms, dbconfig.secret())

        if not badgecode.matches(expected, stored):
            respond(409, {
                "code": code,
                "valid": False,
                "status": "TAMPERED",
                "message": "This badge record does not match its own "
                           "signature and cannot be trusted.",
            })
            return

        if int(row["revoked"]) == 1:
            respond(410, {
                "code": code,
                "valid": False,
                "status": "REVOKED",
                "message": "This badge was issued but has since been revoked.",
            })
            return

        # The response shape stays the same whichever way the badge was
        # issued: a claim badge describes itself through the claim it
        # came from, so an employer sees the certificate title and its
        # issuer where they would otherwise see a module and its unit.
        module_title = row["module_title"]
        module_unit = row["module_unit"]
        if module_title is None:
            module_title = row["claim_title"]
            issuer = row["claim_issuer"]
            module_unit = ("Verified by an administrator" if not issuer
                           else "Issued by %s, verified by an administrator" % issuer)
        if module_title is None:
            module_title = "Skill badge"
        if module_unit is None:
            module_unit = ""

        module = {
            "id": module_id,
            "title": module_title,
            "unit": module_unit,
        }

        # Key order here matches com.badgeportal.Json in VerifyServlet,
        # so the two responses compare equal field for field.
        respond(200, {
            "code": code,
            "valid": True,
            "status": "VALID",
            "student": {
                "id": student_id,
                "name": row["student_name"],
                "email": row["student_email"],
            },
            "module": module,
            "tier": row["tier"],
            "score": float(row["score"]),
            "issuedAt": utc(issued_at_ms),
            "issuedAtMs": issued_at_ms,
        })

    except Exception as exc:                      # noqa: BLE001 -- CGI top level
        sys.stderr.write("verify.py failed for code %r: %r\n" % (code, exc))
        respond(500, {
            "code": code,
            "valid": False,
            "status": "ERROR",
            "message": "Verification service is temporarily unavailable.",
        })
    finally:
        if conn is not None:
            try:
                conn.close()      # and with the process, it dies anyway
            except Exception:     # noqa: BLE001
                pass


if __name__ == "__main__":
    main()
