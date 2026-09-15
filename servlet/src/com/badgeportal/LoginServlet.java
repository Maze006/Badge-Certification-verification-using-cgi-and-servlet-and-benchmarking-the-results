package com.badgeportal;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.SQLException;

/**
 * Page 3 of the flow: authentication.
 *
 *     GET  /login?role=student   renders the student sign-in form
 *     GET  /login?role=admin     renders the admin sign-in form
 *     POST /login                checks the credentials
 *
 * The form is rendered by a servlet rather than served as a static page
 * because the role chosen on the previous page has to travel with it and
 * be visible in the heading -- "dynamically display a login screen based
 * on the role chosen".
 *
 * The role is carried in a hidden field and re-checked server-side
 * against the account's own role. It is a statement of which door the
 * visitor knocked on, never a grant of anything: a student's correct
 * password submitted through the admin form fails.
 */
public class LoginServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /** One message for every failure. See Accounts.authenticate(). */
    private static final String GENERIC_ERROR = "Email or password is incorrect.";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        Accounts.Account already = Auth.current(req);
        if (already != null) {
            resp.sendRedirect(req.getContextPath() + Auth.homeFor(already));
            return;
        }
        render(req, resp, roleOf(req), "", "");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        String role  = roleOf(req);
        String email = trim(req.getParameter("email"));
        String password = req.getParameter("password");

        if (email.isEmpty() || password == null || password.isEmpty()) {
            render(req, resp, role, "Enter both an email and a password.", email);
            return;
        }

        Accounts.Account account;
        try {
            account = Accounts.authenticate(email, password, role);
        } catch (SQLException e) {
            log("[badgeportal] login failed for " + email, e);
            render(req, resp, role,
                   "The sign-in service is temporarily unavailable.", email);
            return;
        }

        if (account == null) {
            // Deliberately does not distinguish unknown email, wrong
            // password, disabled account or wrong role.
            render(req, resp, role, GENERIC_ERROR, email);
            return;
        }

        Auth.signIn(req, account);
        Accounts.recordLogin(account.accountId);
        resp.sendRedirect(req.getContextPath() + Auth.homeFor(account));
    }

    // -----------------------------------------------------------------
    private void render(HttpServletRequest req, HttpServletResponse resp,
                        String role, String error, String email)
            throws IOException {

        boolean admin = "ADMIN".equals(role);
        String ctx = req.getContextPath();

        resp.setContentType("text/html;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        if (!error.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }

        PrintWriter out = resp.getWriter();
        out.print(Layout.head((admin ? "Admin" : "Student") + " sign in", "", ctx));
        out.print("<main class='wrap narrow'>");

        out.print("<header class='page-head'>"
                + "<p class='eyebrow'><a href='" + ctx + "/role.html'>"
                + "&larr; Choose a different role</a></p>"
                + "<h1>" + (admin ? "Admin sign in" : "Student sign in") + "</h1>"
                + "<p class='sub'>"
                + (admin
                   ? "Review and verify the badges students have submitted."
                   : "Upload your skill-badge certification and track your badges.")
                + "</p></header>");

        out.print("<div class='card'>");

        if (!error.isEmpty()) {
            out.print("<p class='form-error' role='alert'>&#10007; "
                    + Json.html(error) + "</p>");
        }

        out.print("<form method='post' action='" + ctx + "/login' autocomplete='on'>"
                + "<input type='hidden' name='role' value='"
                + (admin ? "ADMIN" : "STUDENT") + "'>"

                + "<div class='field'>"
                + "<label for='email'>Email</label>"
                + "<input type='email' id='email' name='email' required "
                + "autocomplete='username' value='" + Json.html(email) + "'>"
                + "</div>"

                + "<div class='field'>"
                + "<label for='password'>Password</label>"
                + "<input type='password' id='password' name='password' required "
                + "autocomplete='current-password'>"
                + "</div>"

                + "<button class='btn' type='submit'>Sign in</button>"
                + "</form>");

        out.print("</div>");
        out.print("</main>");
        out.print(Layout.foot());
    }

    /** Normalises the role from either the query string or the form. */
    private static String roleOf(HttpServletRequest req) {
        String raw = trim(req.getParameter("role"));
        return "admin".equalsIgnoreCase(raw) || "ADMIN".equals(raw)
             ? "ADMIN" : "STUDENT";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
