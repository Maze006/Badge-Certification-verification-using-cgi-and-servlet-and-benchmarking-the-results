package com.badgeportal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Account lookup and password checking.
 *
 * Deliberately small: it answers one question -- "are these credentials
 * good for this role?" -- and nothing else. Session handling lives in
 * Auth, rendering lives in the servlets.
 */
public final class Accounts {

    private static final String SQL_BY_EMAIL =
        "SELECT a.account_id, a.email, a.password_hash, a.role, "
      + "       a.student_id, a.is_active, s.name AS student_name "
      + "FROM accounts a "
      + "LEFT JOIN students s ON s.student_id = a.student_id "
      + "WHERE a.email = ?";

    private static final String SQL_TOUCH_LOGIN =
        "UPDATE accounts SET last_login_at = ? WHERE account_id = ?";

    private Accounts() { }

    /** A signed-in identity. Immutable; safe to hold in a session. */
    public static final class Account {
        public final int    accountId;
        public final String email;
        public final String role;        // "ADMIN" or "STUDENT"
        public final int    studentId;   // 0 for admins
        public final String displayName;

        Account(int accountId, String email, String role,
                int studentId, String displayName) {
            this.accountId   = accountId;
            this.email       = email;
            this.role        = role;
            this.studentId   = studentId;
            this.displayName = displayName;
        }

        public boolean isAdmin()   { return "ADMIN".equals(role); }
        public boolean isStudent() { return "STUDENT".equals(role); }
    }

    /**
     * Checks credentials and returns the account, or null.
     *
     * Returns null for every kind of failure -- unknown email, wrong
     * password, disabled account, or right password at the wrong role's
     * door -- and the caller shows one generic message for all of them.
     * Telling a visitor which of those happened tells an attacker which
     * email addresses are worth attacking.
     *
     * @param expectedRole the role the visitor claimed on the role page.
     *                     A student's correct password fails here if
     *                     submitted through the admin form: roles are
     *                     bound to the account, not chosen at login.
     */
    public static Account authenticate(String email, String password,
                                       String expectedRole) throws SQLException {
        if (email == null || password == null || expectedRole == null) {
            return null;
        }
        email = email.trim().toLowerCase();

        Connection conn = null;
        try {
            conn = Db.borrow();
            PreparedStatement ps = conn.prepareStatement(SQL_BY_EMAIL);
            try {
                ps.setString(1, email);
                ResultSet rs = ps.executeQuery();
                try {
                    if (!rs.next()) {
                        // No such account. Hash anyway, so that a
                        // missing email and a wrong password take the
                        // same time -- otherwise the response time
                        // alone reveals which emails are registered.
                        PasswordHash.verifyDummy(password);
                        return null;
                    }

                    String storedHash = rs.getString("password_hash");
                    String role       = rs.getString("role");
                    boolean active    = rs.getInt("is_active") == 1;

                    if (!PasswordHash.verify(password, storedHash)) return null;
                    if (!active) return null;
                    if (!expectedRole.equalsIgnoreCase(role)) return null;

                    int studentId = rs.getInt("student_id");
                    if (rs.wasNull()) studentId = 0;

                    String name = rs.getString("student_name");
                    if (name == null || name.trim().isEmpty()) {
                        name = "Administrator";
                    }

                    return new Account(rs.getInt("account_id"),
                                       rs.getString("email"),
                                       role, studentId, name);
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
            }
        } finally {
            Db.release(conn);
        }
    }

    /** Records a successful sign-in. Failure here must not fail the login. */
    public static void recordLogin(int accountId) {
        Connection conn = null;
        try {
            conn = Db.borrow();
            PreparedStatement ps = conn.prepareStatement(SQL_TOUCH_LOGIN);
            try {
                ps.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
                ps.setInt(2, accountId);
                ps.executeUpdate();
            } finally {
                ps.close();
            }
        } catch (SQLException ignored) {
            // Cosmetic bookkeeping. Never block a valid sign-in over it.
        } finally {
            Db.release(conn);
        }
    }
}
