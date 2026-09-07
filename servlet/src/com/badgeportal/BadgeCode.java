package com.badgeportal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Tamper-evident verification codes.
 *
 * A code looks like:   SF-3A9F-B21C-7E04
 *                      ^^ module prefix, then 12 hex digits of a
 *                         SHA-256 digest in three groups of four.
 *
 * The digest is taken over
 *
 *     studentId : moduleId : issuedAtMillis : secret
 *
 * so the code is bound to the exact badge it was issued for. Because
 * the badge row stores studentId, moduleId and issuedAtMillis, the
 * verifier can RECOMPUTE the expected code from the row itself and
 * compare. Anyone who edits the database -- pointing a valid-looking
 * code at a different student, say -- cannot produce a matching digest
 * without the secret, so the mismatch is detected at verification time.
 *
 * NOTE: this exact algorithm is mirrored byte-for-byte in
 * cgi/badgecode.py. The two implementations MUST stay in step, and
 * bench/benchmark.py asserts that they agree.
 */
public final class BadgeCode {

    private BadgeCode() { }

    /** Builds the canonical string that gets hashed. */
    public static String payload(int studentId, int moduleId, long issuedAtMs, String secret) {
        return studentId + ":" + moduleId + ":" + issuedAtMs + ":" + secret;
    }

    /** SHA-256 of the payload, lower-case hex. */
    public static String digestHex(int studentId, int moduleId, long issuedAtMs, String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(
                payload(studentId, moduleId, issuedAtMs, secret).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; this cannot happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * The public verification code for a badge.
     *
     * @param prefix     two-letter module stem, e.g. "SF"
     */
    public static String generate(String prefix, int studentId, int moduleId,
                                  long issuedAtMs, String secret) {
        String hex = digestHex(studentId, moduleId, issuedAtMs, secret)
                        .substring(0, 12).toUpperCase();
        return prefix.toUpperCase() + "-"
             + hex.substring(0, 4) + "-"
             + hex.substring(4, 8) + "-"
             + hex.substring(8, 12);
    }

    /** Badge tier from the module score (stretch goal). */
    public static String tierFor(double score) {
        if (score >= 90.0) return "GOLD";
        if (score >= 75.0) return "SILVER";
        return "BRONZE";
    }

    /**
     * Constant-time-ish comparison of two codes. Verification codes are
     * public values rather than passwords, so this is belt-and-braces,
     * but it costs nothing and keeps the habit right.
     */
    public static boolean matches(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /** Normalises user input: trims, upper-cases, tolerates missing dashes. */
    public static String normalise(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase().replace(" ", "");
        if (s.indexOf('-') < 0 && s.length() == 14) {
            // e.g. SF3A9FB21C7E04 -> SF-3A9F-B21C-7E04
            s = s.substring(0, 2) + "-" + s.substring(2, 6) + "-"
              + s.substring(6, 10) + "-" + s.substring(10, 14);
        }
        return s;
    }
}
