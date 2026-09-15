package com.badgeportal;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;

/**
 * Admin dashboard.
 *
 *     GET /admin/dashboard
 *
 * PHASE 2 SCOPE: this is a placeholder. It proves the session and the
 * access control work, and shows the size of the pending queue. The
 * review interface -- choosing a claim, viewing its evidence, approving
 * or rejecting it -- is Phase 5.
 *
 * Reaching this at all means AuthFilter has already established that
 * there is a session and that it belongs to an admin.
 */
public class AdminServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        Accounts.Account account = Auth.current(req);
        String ctx = req.getContextPath();

        resp.setContentType("text/html;charset=UTF-8");

        int pending = countPending();

        StringBuilder out = new StringBuilder(2000);
        out.append(Layout.headSignedIn("Admin dashboard", "", account, ctx))
           .append("<main class='wrap'>")
           .append("<header class='page-head'><h1>Admin dashboard</h1>")
           .append("<p class='sub'>Verify the badges students have submitted.</p>")
           .append("</header>")

           .append("<div class='tiles'>")
           .append("<div class='tile'><h3>Pending claims</h3>")
           .append("<p class='stat-number'>").append(pending).append("</p>")
           .append("<p>Awaiting review.</p></div>")
           .append("<a class='tile' href='").append(ctx).append("/wallet'>")
           .append("<h3>Student wallets &rarr;</h3>")
           .append("<p>Browse any student and the badges they hold.</p></a>")
           .append("<a class='tile' href='").append(ctx).append("/verify.html'>")
           .append("<h3>Verify a code &rarr;</h3>")
           .append("<p>Check any verification code, through either ")
           .append("implementation.</p></a>")
           .append("</div>")

           .append("<h2 class='section'>Review queue</h2>")
           .append("<div class='card empty'>")
           .append("<p>The review interface arrives in the next stage of the ")
           .append("build. ").append(pending)
           .append(" claim(s) are waiting in the database.</p>")
           .append("</div>")

           .append("</main>")
           .append(Layout.foot());

        resp.getWriter().print(out);
    }

    private int countPending() {
        Connection conn = null;
        try {
            conn = Db.borrow();
            Statement st = conn.createStatement();
            try {
                ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM badge_claims WHERE status = 'PENDING'");
                try {
                    return rs.next() ? rs.getInt(1) : 0;
                } finally {
                    rs.close();
                }
            } finally {
                st.close();
            }
        } catch (SQLException e) {
            log("[badgeportal] could not count pending claims", e);
            return 0;
        } finally {
            Db.release(conn);
        }
    }
}
