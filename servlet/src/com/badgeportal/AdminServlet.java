package com.badgeportal;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Admin dashboard: the review queue.
 *
 *     GET /admin/dashboard
 *
 * Shows every pending claim with its evidence, so an administrator can
 * look at what the student actually submitted before deciding. Approving
 * posts to ReviewServlet, which is where the badge is minted.
 *
 * Reaching this at all means AuthFilter has established there is a
 * session and that it belongs to an admin.
 */
public class AdminServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final String SQL_QUEUE =
        "SELECT c.claim_id, c.student_id, c.title, c.issuer, c.external_url, "
      + "       c.evidence_path, c.evidence_name, c.proposed_tier, c.status, "
      + "       c.submitted_at, c.review_note, s.name AS student_name, "
      + "       s.email AS student_email "
      + "FROM badge_claims c JOIN students s ON s.student_id = c.student_id "
      + "WHERE c.status = 'PENDING' ORDER BY c.submitted_at ASC";

    private static final String SQL_RECENT =
        "SELECT c.claim_id, c.title, c.status, c.reviewed_at, "
      + "       s.name AS student_name, b.verification_code "
      + "FROM badge_claims c "
      + "JOIN students s ON s.student_id = c.student_id "
      + "LEFT JOIN badges b ON b.claim_id = c.claim_id "
      + "WHERE c.status <> 'PENDING' "
      + "ORDER BY c.reviewed_at DESC LIMIT 8";

    private static final String SQL_STATS =
        "SELECT (SELECT COUNT(*) FROM badge_claims WHERE status='PENDING') AS pending, "
      + "       (SELECT COUNT(*) FROM badges) AS badges, "
      + "       (SELECT COUNT(*) FROM badges WHERE source='ADMIN_APPROVED') AS approved";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        Accounts.Account account = Auth.current(req);
        String ctx = req.getContextPath();
        resp.setContentType("text/html;charset=UTF-8");

        int pending = 0, badges = 0, approved = 0;
        List<String> queue = new ArrayList<String>();
        StringBuilder recent = new StringBuilder();

        Connection conn = null;
        try {
            conn = Db.borrow();

            Statement st = conn.createStatement();
            try {
                ResultSet rs = st.executeQuery(SQL_STATS);
                try {
                    if (rs.next()) {
                        pending  = rs.getInt("pending");
                        badges   = rs.getInt("badges");
                        approved = rs.getInt("approved");
                    }
                } finally { rs.close(); }

                rs = st.executeQuery(SQL_QUEUE);
                try {
                    while (rs.next()) queue.add(renderClaim(rs, ctx, req));
                } finally { rs.close(); }

                rs = st.executeQuery(SQL_RECENT);
                try {
                    while (rs.next()) recent.append(renderRecent(rs, ctx));
                } finally { rs.close(); }
            } finally { st.close(); }

        } catch (SQLException e) {
            log("[badgeportal] admin dashboard failed", e);
        } finally {
            Db.release(conn);
        }

        StringBuilder out = new StringBuilder(12000);
        out.append(Layout.headSignedIn("Admin dashboard", "dashboard", account, ctx))
           .append("<main class='wrap'>")
           .append("<header class='page-head'><h1>Admin dashboard</h1>")
           .append("<p class='sub'>Verify what students have submitted. ")
           .append("No verification code exists until you approve.</p></header>")
           .append(banner(req))

           .append("<div class='tiles'>")
           .append("<div class='tile'><h3>Awaiting review</h3>")
           .append("<p class='stat-number'>").append(pending).append("</p>")
           .append("<p>Claims you have not decided yet.</p></div>")
           .append("<div class='tile'><h3>Badges issued</h3>")
           .append("<p class='stat-number'>").append(badges).append("</p>")
           .append("<p>").append(approved).append(" from approved claims.</p></div>")
           .append("<a class='tile' href='").append(ctx).append("/admin/wallets'>")
           .append("<h3>Student wallets &rarr;</h3>")
           .append("<p>Browse any student and the badges they hold.</p></a>")
           .append("</div>");

        out.append("<h2 class='section'>Review queue <span class='count'>")
           .append(queue.size()).append("</span></h2>");

        if (queue.isEmpty()) {
            out.append("<div class='card empty'><p>Nothing waiting. ")
               .append("Every submission has been decided.</p></div>");
        } else {
            for (String card : queue) out.append(card);
        }

        out.append("<h2 class='section'>Recently decided</h2>");
        if (recent.length() == 0) {
            out.append("<div class='card empty'><p>No decisions yet.</p></div>");
        } else {
            out.append("<div class='card'><table class='grid'><thead><tr>")
               .append("<th>Student</th><th>Badge</th><th>Outcome</th>")
               .append("<th>Code</th></tr></thead><tbody>")
               .append(recent).append("</tbody></table></div>");
        }

        out.append("</main>").append(Layout.foot());
        resp.getWriter().print(out);
    }

    // -----------------------------------------------------------------
    private String renderClaim(ResultSet rs, String ctx, HttpServletRequest req)
            throws SQLException {

        int claimId   = rs.getInt("claim_id");
        String tier   = rs.getString("proposed_tier");
        String issuer = rs.getString("issuer");
        String url    = rs.getString("external_url");
        String file   = rs.getString("evidence_path");
        String fname  = rs.getString("evidence_name");

        StringBuilder b = new StringBuilder(2000);
        b.append("<article class='card review-card'>")
         .append("<div class='review-head'><div>")
         .append("<h3 class='review-title'>").append(Json.html(rs.getString("title")))
         .append("</h3>")
         .append("<p class='review-meta'>")
         .append(Json.html(rs.getString("student_name")))
         .append(" &middot; <span class='mono'>")
         .append(Json.html(rs.getString("student_email"))).append("</span>");
        if (issuer != null && !issuer.isEmpty()) {
            b.append(" &middot; issued by ").append(Json.html(issuer));
        }
        b.append(" &middot; submitted ")
         .append(TimeFmt.utc(rs.getTimestamp("submitted_at").getTime()))
         .append(" UTC</p></div>")
         .append("<span class='pill'>asks ").append(Json.html(tier)).append("</span>")
         .append("</div>");

        // ---- evidence ------------------------------------------------
        b.append("<div class='review-evidence'>");
        if (url != null && !url.isEmpty()) {
            b.append("<a class='btn small ghost' target='_blank' rel='noopener' href='")
             .append(Json.html(url)).append("'>Open link &nearr;</a>");
        }
        if (file != null && !file.isEmpty()) {
            b.append("<a class='btn small ghost' target='_blank' href='").append(ctx)
             .append("/files/evidence?claim=").append(claimId).append("'>View ")
             .append(Json.html(fname == null ? "certificate" : fname))
             .append(" &nearr;</a>");
        }
        if ((url == null || url.isEmpty()) && (file == null || file.isEmpty())) {
            b.append("<span class='review-noevidence'>No evidence attached.</span>");
        }
        b.append("</div>");

        // ---- decision ------------------------------------------------
        b.append("<form method='post' action='").append(ctx)
         .append("/admin/review' class='review-form'>")
         .append(Auth.csrfField(req))
         .append("<input type='hidden' name='claimId' value='").append(claimId).append("'>")

         .append("<div class='review-controls'>")
         .append("<label for='tier").append(claimId).append("'>Award tier</label>")
         .append("<select id='tier").append(claimId).append("' name='tier'>")
         .append(option("BRONZE", tier)).append(option("SILVER", tier))
         .append(option("GOLD", tier))
         .append("</select>")
         .append("<input type='text' name='note' maxlength='400' ")
         .append("placeholder='Note (required to reject)'>")
         .append("<button class='btn' type='submit' name='action' value='approve'>")
         .append("Approve &amp; issue</button>")
         .append("<button class='btn ghost' type='submit' name='action' value='reject'>")
         .append("Reject</button>")
         .append("</div></form>")
         .append("</article>");
        return b.toString();
    }

    private String renderRecent(ResultSet rs, String ctx) throws SQLException {
        String status = rs.getString("status");
        String code   = rs.getString("verification_code");
        StringBuilder b = new StringBuilder(400);
        b.append("<tr><td>").append(Json.html(rs.getString("student_name")))
         .append("</td><td>").append(Json.html(rs.getString("title")))
         .append("</td><td><span class='status status-").append(status.toLowerCase())
         .append("'>").append(status).append("</span></td><td>");
        if (code != null) {
            b.append("<a class='mono' target='_blank' href='").append(ctx)
             .append("/verify.html?code=").append(code).append("'>")
             .append(code).append("</a>");
        } else {
            b.append("<span class='mono'>&mdash;</span>");
        }
        return b.append("</td></tr>").toString();
    }

    private static String option(String value, String selected) {
        return "<option value='" + value + "'"
             + (value.equals(selected) ? " selected" : "") + ">"
             + value.charAt(0) + value.substring(1).toLowerCase() + "</option>";
    }

    private String banner(HttpServletRequest req) {
        String done = req.getParameter("done");
        if (done != null && !done.isEmpty()) {
            return "<p class='form-note' role='status'>&#10003; "
                 + Json.html(done) + "</p>";
        }
        String error = req.getParameter("error");
        if (error != null && !error.isEmpty()) {
            return "<p class='form-error' role='alert'>&#10007; "
                 + Json.html(error) + "</p>";
        }
        return "";
    }
}
