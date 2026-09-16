"""
Tamper-evident verification codes -- Python side.

This is a line-for-line mirror of servlet/src/com/badgeportal/BadgeCode.java.
The two MUST agree: the CGI verifier recomputes the digest of a badge
row exactly the way the servlet issuer computed it, and if the two
implementations ever drifted apart, every badge would suddenly look
tampered with when checked through CGI.

bench/benchmark.py guards against that drift by asserting that both
endpoints return the same JSON for the same code.
"""

import hashlib

# Stand-ins used when a badge came from an approved claim rather than a
# course module. A claim badge has no module, so there is no module id
# to hash and no two-letter module prefix to print.
#
# These MUST match BadgeCode.CLAIM_PREFIX and BadgeCode.NO_MODULE on the
# Java side. Change either and every claim-issued badge stops verifying,
# because the digest recomputed here would no longer match the digest
# computed by the issuer.
CLAIM_PREFIX = "CB"
NO_MODULE = 0


def payload(student_id, module_id, issued_at_ms, secret):
    """The canonical string that gets hashed."""
    return "%d:%d:%d:%s" % (student_id, module_id, issued_at_ms, secret)


def digest_hex(student_id, module_id, issued_at_ms, secret):
    """SHA-256 of the payload, lower-case hex."""
    raw = payload(student_id, module_id, issued_at_ms, secret).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def generate(prefix, student_id, module_id, issued_at_ms, secret):
    """
    The public verification code for a badge, e.g. SF-3A9F-B21C-7E04.

    Twelve hex digits of the digest, upper-cased, in three groups of
    four, behind the two-letter module prefix.
    """
    hex12 = digest_hex(student_id, module_id, issued_at_ms, secret)[:12].upper()
    return "%s-%s-%s-%s" % (prefix.upper(), hex12[0:4], hex12[4:8], hex12[8:12])


def tier_for(score):
    """Badge tier from the module score."""
    if score >= 90.0:
        return "GOLD"
    if score >= 75.0:
        return "SILVER"
    return "BRONZE"


def matches(a, b):
    """Length-safe comparison of two codes."""
    if a is None or b is None or len(a) != len(b):
        return False
    diff = 0
    for x, y in zip(a, b):
        diff |= ord(x) ^ ord(y)
    return diff == 0


def normalise(raw):
    """Trims, upper-cases, and tolerates a code typed without dashes."""
    if not raw:
        return ""
    s = raw.strip().upper().replace(" ", "")
    if "-" not in s and len(s) == 14:
        s = "%s-%s-%s-%s" % (s[0:2], s[2:6], s[6:10], s[10:14])
    return s
