package com.badgeportal;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Session handling for signed-in users, and the CSRF token.
 *
 * All session keys live here rather than as string literals scattered
 * through the servlets, so there is exactly one place where the shape of
 * a session is defined.
 */
public final class Auth {

    private static final String KEY_ACCOUNT = "badgeportal.account";
    private static final String KEY_CSRF    = "badgeportal.csrf";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Auth() { }

    /**
     * Establishes a signed-in session.
     *
     * changeSessionId() is the important line. Without it, a session id
     * issued to an anonymous visitor survives their sign-in -- so an
     * attacker who can plant a known id in someone's browser before they
     * log in ends up holding a session that is now authenticated as
     * them. That is session fixation, and rotating the id at exactly
     * this moment is the standard defence. It is a Servlet 3.1 method,
     * which is what Tomcat 8.5 implements.
     */
    public static void signIn(HttpServletRequest req, Accounts.Account account) {
        HttpSession old = req.getSession(false);
        if (old != null) {
            req.changeSessionId();
        }
        HttpSession session = req.getSession(true);
        session.setAttribute(KEY_ACCOUNT, account);
        session.setAttribute(KEY_CSRF, newToken());
    }

    /** Ends the session entirely rather than just clearing attributes. */
    public static void signOut(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /** The signed-in account, or null. */
    public static Accounts.Account current(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        Object o = session.getAttribute(KEY_ACCOUNT);
        return (o instanceof Accounts.Account) ? (Accounts.Account) o : null;
    }

    /** The CSRF token for this session, or "" when signed out. */
    public static String csrfToken(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return "";
        Object o = session.getAttribute(KEY_CSRF);
        return (o == null) ? "" : o.toString();
    }

    /**
     * Checks the CSRF token on a state-changing POST.
     *
     * Without this, any page on the internet can contain a form that
     * posts to this application, and a signed-in visitor's browser will
     * attach their session cookie to it automatically. The token proves
     * the request came from a page this application rendered, because a
     * cross-origin page cannot read the session to learn the token.
     */
    public static boolean csrfValid(HttpServletRequest req) {
        String expected = csrfToken(req);
        String supplied = req.getParameter("csrf");
        if (expected == null || expected.isEmpty()) return false;
        if (supplied == null || supplied.length() != expected.length()) return false;
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ supplied.charAt(i);
        }
        return diff == 0;
    }

    /** A hidden input carrying the CSRF token, for embedding in forms. */
    public static String csrfField(HttpServletRequest req) {
        return "<input type='hidden' name='csrf' value='"
             + Json.html(csrfToken(req)) + "'>";
    }

    /** Where a given role belongs after signing in. */
    public static String homeFor(Accounts.Account account) {
        return account != null && account.isAdmin()
             ? "/admin/dashboard"
             : "/student/dashboard";
    }

    private static String newToken() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
