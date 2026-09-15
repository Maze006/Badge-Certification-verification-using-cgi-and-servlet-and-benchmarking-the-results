"""
Generates a password hash in the format the portal stores.

    python scripts/make_password_hash.py "SomePassword"
    python scripts/make_password_hash.py "SomePassword" --sql admin@college.edu ADMIN

Format stored in accounts.password_hash:

    pbkdf2_sha256$<iterations>$<salt_b64>$<key_b64>

One self-describing string rather than separate hash / salt / iteration
columns. Everything needed to verify a password travels with it, so the
iteration count can be raised later without invalidating existing rows:
old hashes keep verifying at their own cost, new ones are written at the
new cost. Splitting those values across columns makes that migration
painful for no benefit.

PBKDF2-HMAC-SHA256 is used because it is available unmodified in BOTH
runtimes -- hashlib.pbkdf2_hmac here, and javax.crypto
SecretKeyFactory("PBKDF2WithHmacSHA256") in com.badgeportal.PasswordHash
-- with no third-party dependency on either side. That mirrors how
BadgeCode.java and badgecode.py already mirror each other.

A note on the choice: PBKDF2 is deliberately slow, which is the point
for passwords and the exact opposite of what the rest of this project
optimises for. bcrypt, scrypt or Argon2 would resist GPU attack better,
but none ship with the JDK, and adding a jar would undermine the "no
external dependencies" property the benchmark relies on.
"""

import argparse
import base64
import hashlib
import os
import secrets
import sys

ALGORITHM = "pbkdf2_sha256"
ITERATIONS = 120_000
SALT_BYTES = 16
KEY_BYTES = 32


def hash_password(password, salt=None, iterations=ITERATIONS):
    """Returns the encoded hash string for a plaintext password."""
    if salt is None:
        salt = secrets.token_bytes(SALT_BYTES)
    key = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"),
                              salt, iterations, dklen=KEY_BYTES)
    return "%s$%d$%s$%s" % (
        ALGORITHM,
        iterations,
        base64.b64encode(salt).decode("ascii"),
        base64.b64encode(key).decode("ascii"),
    )


def verify_password(password, encoded):
    """Checks a plaintext password against an encoded hash."""
    try:
        algorithm, iterations, salt_b64, key_b64 = encoded.split("$")
    except ValueError:
        return False
    if algorithm != ALGORITHM:
        return False
    salt = base64.b64decode(salt_b64)
    expected = base64.b64decode(key_b64)
    actual = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"),
                                 salt, int(iterations), dklen=len(expected))
    # Constant time: never leak how much of the hash matched.
    return secrets.compare_digest(actual, expected)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("password")
    ap.add_argument("--sql", nargs=2, metavar=("EMAIL", "ROLE"),
                    help="emit a ready-to-paste INSERT for that email and role")
    ap.add_argument("--student-id", type=int, default=None,
                    help="link the account to a students row (STUDENT role)")
    args = ap.parse_args()

    encoded = hash_password(args.password)

    if not verify_password(args.password, encoded):
        print("self-check FAILED -- refusing to emit a hash", file=sys.stderr)
        return 1

    if args.sql:
        email, role = args.sql
        student = str(args.student_id) if args.student_id else "NULL"
        print("INSERT INTO accounts (email, password_hash, role, student_id, "
              "created_at)\nVALUES ('%s', '%s', '%s', %s, NOW(3));"
              % (email, encoded, role.upper(), student))
    else:
        print(encoded)

    return 0


if __name__ == "__main__":
    sys.exit(main())
