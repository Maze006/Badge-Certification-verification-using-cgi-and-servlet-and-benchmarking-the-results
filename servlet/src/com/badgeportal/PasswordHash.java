package com.badgeportal;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing, in the format stored in accounts.password_hash:
 *
 *     pbkdf2_sha256$120000$&lt;salt_b64&gt;$&lt;key_b64&gt;
 *
 * This is the Java half of a mirrored pair, exactly like BadgeCode.java
 * and badgecode.py. scripts/make_password_hash.py writes these strings;
 * this class verifies them. The seeded accounts in
 * sql/03_accounts_and_claims.sql were hashed by the Python side and are
 * read back by this one, so the two must agree byte for byte.
 *
 * PBKDF2-HMAC-SHA256 was chosen because it ships in both runtimes with
 * no third-party dependency -- javax.crypto here, hashlib there. bcrypt,
 * scrypt or Argon2 resist GPU attack better, but none are in the JDK,
 * and adding a jar would undermine the no-external-dependencies property
 * the rest of this project relies on.
 *
 * Note the deliberate inversion of this project's usual goal: everything
 * else here is optimised to be fast, and this is optimised to be slow.
 * A password hash that verifies quickly is one an attacker can guess
 * quickly. Roughly 70 ms per verification is the point, not a defect.
 */
public final class PasswordHash {

    private static final String PREFIX = "pbkdf2_sha256";
    private static final String JCE_ALGORITHM = "PBKDF2WithHmacSHA256";

    private static final int ITERATIONS = 120000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;   // 32 bytes, matching dklen

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * A syntactically valid hash of a password nobody knows, used to
     * spend the same time on a missing account as on a real one. See
     * the comment in Accounts.authenticate().
     */
    private static final String DUMMY = hash(randomString());

    private PasswordHash() { }

    /** Hashes a plaintext password with a fresh random salt. */
    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, salt, ITERATIONS, KEY_BITS);
        return PREFIX + "$" + ITERATIONS + "$"
             + Base64.getEncoder().encodeToString(salt) + "$"
             + Base64.getEncoder().encodeToString(key);
    }

    /**
     * Verifies a plaintext password against an encoded hash.
     *
     * The iteration count is read FROM the stored string rather than
     * assumed, which is the whole reason it is stored there: raising
     * ITERATIONS later leaves existing accounts verifying correctly at
     * their original cost instead of locking everyone out.
     */
    public static boolean verify(String password, String encoded) {
        if (password == null || encoded == null) return false;

        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;

        int iterations;
        byte[] salt, expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (iterations <= 0 || salt.length == 0 || expected.length == 0) {
            return false;
        }

        byte[] actual = derive(password, salt, iterations, expected.length * 8);

        // Constant time: never leak how many leading bytes matched.
        return MessageDigest.isEqual(actual, expected);
    }

    /**
     * Spends roughly the same time a real verification would, and always
     * returns false. Called when no account exists for the submitted
     * email, so that a missing account and a wrong password take
     * indistinguishable time.
     */
    public static boolean verifyDummy(String password) {
        verify(password == null ? "" : password, DUMMY);
        return false;
    }

    private static byte[] derive(String password, byte[] salt,
                                 int iterations, int keyBits) {
        PBEKeySpec spec = null;
        try {
            spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyBits);
            return SecretKeyFactory.getInstance(JCE_ALGORITHM)
                                   .generateSecret(spec)
                                   .getEncoded();
        } catch (Exception e) {
            // PBKDF2WithHmacSHA256 is required of every Java 8 runtime;
            // if it is genuinely missing, failing loudly beats falling
            // back to something weaker.
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            if (spec != null) spec.clearPassword();
        }
    }

    private static String randomString() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }
}
