package com.badgeportal;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Student dashboard.
 *
 *     GET /student/dashboard
 *
 * PHASE 2 SCOPE: this is a placeholder. It proves the session carries
 * the right student and that the access control works. Submitting a
 * claim -- the title, the link, the uploaded certificate, the proposed
 * tier -- is Phase 4.
 *
 * Note that it never reads a student id from the request. The id comes
 * from the session, so a student cannot view another student's data by
 * editing a URL. The existing /wallet page takes ?studentId= from the
 * query string, which is fine while it is a public directory but is
 * exactly the pattern to avoid once pages show private data.
 */
public class StudentServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final String SQL_COUNTS =
        "SELECT "
      + " (SELECT COUNT(*) FROM badges       WHERE student_id = ?) AS badges, "
      + " (SELECT COUNT(*) FROM badge_claims WHERE student_id = ? "
      + "         AND status = 'PENDING') AS pending";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        Accounts.Account account = Auth.current(req);
        String ctx = req.getContextPath();

        resp.setContentType("text/html;charset=UTF-8");

        int badges = 0, pending = 0;
        Connection conn = null;
        try {
            conn = Db.borrow();
            PreparedStatement ps = conn.prepareStatement(SQL_COUNTS);
            try {
                ps.setInt(1, account.studentId);
                ps.setInt(2, account.studentId);
                ResultSet rs = ps.executeQuery();
                try {
                    if (rs.next()) {
                        badges  = rs.getInt("badges");
                        pending = rs.getInt("pending");
                    }
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            log("[badgeportal] student dashboard failed for account "
                + account.accountId, e);
        } finally {
            Db.release(conn);
        }

        StringBuilder out = new StringBuilder(2000);
        out.append(Layout.headSignedIn("Student dashboard", "", account, ctx))
           .append("<main class='wrap'>")
           .append("<header class='page-head'><h1>")
           .append(Json.html(account.displayName)).append("</h1>")
           .append("<p class='sub'>").append(Json.html(account.email))
           .append("</p></header>")

           .append("<div class='tiles'>")
           .append("<div class='tile'><h3>Badges earned</h3>")
           .append("<p class='stat-number'>").append(badges).append("</p>")
           .append("<p>Verified credentials you hold.</p></div>")
           .append("<div class='tile'><h3>Awaiting review</h3>")
           .append("<p class='stat-number'>").append(pending).append("</p>")
           .append("<p>Submitted, not yet verified.</p></div>")
           .append("<a class='tile' href='").append(ctx)
           .append("/wallet?studentId=").append(account.studentId).append("'>")
           .append("<h3>Open your wallet &rarr;</h3>")
           .append("<p>Every badge with its verification code.</p></a>")
           .append("</div>")

           .append("<h2 class='section'>Upload a badge</h2>")
           .append("<div class='card empty'>")
           .append("<p>The submission form arrives in the next stage of the ")
           .append("build. It will take a title, a link, a certificate file ")
           .append("and a proposed tier &mdash; and an administrator verifies ")
           .append("it before any verification code is issued.</p>")
           .append("</div>")

           .append("</main>")
           .append(Layout.foot());

        resp.getWriter().print(out);
    }
}
