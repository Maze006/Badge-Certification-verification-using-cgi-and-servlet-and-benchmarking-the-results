package com.badgeportal;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Student wallets -- the administrator's view of the cohort.
 *
 *     GET /badgeportal/admin/wallets              -> pick a student
 *     GET /badgeportal/admin/wallets?studentId=3  -> that student's badges
 *
 * ADMIN ONLY. This lists every student and opens any wallet, so it
 * exposes the whole cohort. It used to sit at /wallet with no
 * protection at all. A student reads their own badges from their
 * dashboard instead, scoped to their session.
 *
 * Shows every badge the student holds with its verification code and a
 * shareable verify link, plus the modules they have completed but not
 * yet claimed a badge for.
 *
 * This one generates HTML rather than JSON, which is the other half of
 * the "generate dynamic HTTP responses" objective: the same servlet API
 * serves a machine-readable response on /api/verify and a human-readable
 * one here, decided entirely by what the servlet writes to the stream.
 */
public class WalletServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final String SQL_STUDENT =
        "SELECT name, email, enrolled_on FROM students WHERE student_id = ?";

    /**
     * LEFT JOIN, not INNER. A badge issued from an approved claim has no
     * module_id, and an inner join would silently drop it from the
     * wallet -- the page whose entire job is to show every badge a
     * student holds.
     */
    private static final String SQL_BADGES =
        "SELECT b.verification_code, b.tier, b.score, b.issued_at_ms, b.revoked, "
      + "       m.title, m.unit, c.title AS claim_title, c.issuer AS claim_issuer "
      + "FROM badges b "
      + "LEFT JOIN modules      m ON m.module_id = b.module_id "
      + "LEFT JOIN badge_claims c ON c.claim_id  = b.claim_id "
      + "WHERE b.student_id = ? ORDER BY b.issued_at_ms DESC";

    private static final String SQL_UNCLAIMED =
        "SELECT c.module_id, c.score, m.title, m.unit "
      + "FROM module_completions c "
      + "JOIN modules m ON m.module_id = c.module_id "
      + "WHERE c.student_id = ? "
      + "  AND c.module_id NOT IN (SELECT module_id FROM badges WHERE student_id = ?) "
      + "ORDER BY m.module_id";

    private static final String SQL_ALL_STUDENTS =
        "SELECT s.student_id, s.name, COUNT(b.badge_id) AS badge_count "
      + "FROM students s LEFT JOIN badges b ON b.student_id = s.student_id "
      + "GROUP BY s.student_id, s.name ORDER BY s.student_id";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter out = resp.getWriter();

        int studentId;
        try {
            studentId = Integer.parseInt(req.getParameter("studentId").trim());
        } catch (Exception e) {
            studentId = -1;
        }

        Connection conn = null;
        try {
            conn = Db.borrow();
            Accounts.Account viewer = Auth.current(req);
            if (studentId <= 0) {
                renderPicker(out, conn, req.getContextPath(), viewer);
            } else {
                renderWallet(out, conn, studentId, req.getContextPath(), viewer);
            }
        } catch (SQLException e) {
            log("[badgeportal] wallet failed for student " + studentId, e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print(head("Wallet unavailable", req.getContextPath(), Auth.current(req))
                    + "<main class='wrap'><div class='card empty'>"
                    + "<h2>Wallet temporarily unavailable</h2>"
                    + "<p>The database could not be reached. Check that MySQL is "
                    + "running and that the credentials in badgeportal.properties "
                    + "are correct.</p></div></main>" + foot());
        } finally {
            Db.release(conn);
        }
    }

    // -----------------------------------------------------------------
    private void renderPicker(PrintWriter out, Connection conn, String ctx,
                              Accounts.Account viewer) throws SQLException {
        out.print(head("Student wallets", ctx, viewer));
        out.print("<main class='wrap'>");
        out.print("<header class='page-head'><h1>Student wallets</h1>"
                + "<p class='sub'>Pick a student to open their skill-badge wallet.</p>"
                + "</header>");
        out.print("<div class='card'><table class='grid'>"
                + "<thead><tr><th>ID</th><th>Name</th><th>Badges</th><th></th></tr></thead><tbody>");

        Statement st = conn.createStatement();
        try {
            ResultSet rs = st.executeQuery(SQL_ALL_STUDENTS);
            try {
                while (rs.next()) {
                    int id = rs.getInt("student_id");
                    out.print("<tr><td class='mono'>" + id + "</td><td>"
                            + Json.html(rs.getString("name")) + "</td><td class='mono'>"
                            + rs.getInt("badge_count") + "</td>"
                            + "<td><a class='btn small' href='?studentId=" + id
                            + "'>Open wallet</a></td></tr>");
                }
            } finally {
                rs.close();
            }
        } finally {
            st.close();
        }
        out.print("</tbody></table></div></main>" + foot());
    }

    // -----------------------------------------------------------------
    private void renderWallet(PrintWriter out, Connection conn, int studentId,
                              String ctx, Accounts.Account viewer)
            throws SQLException {

        String name = null, email = null, enrolled = null;
        PreparedStatement ps = conn.prepareStatement(SQL_STUDENT);
        try {
            ps.setInt(1, studentId);
            ResultSet rs = ps.executeQuery();
            try {
                if (rs.next()) {
                    name     = rs.getString("name");
                    email    = rs.getString("email");
                    enrolled = String.valueOf(rs.getDate("enrolled_on"));
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }

        if (name == null) {
            out.print(head("Unknown student", ctx, viewer)
                    + "<main class='wrap'><div class='card empty'>"
                    + "<h2>No such student</h2><p>There is no student with id "
                    + studentId + ".</p><p><a class='btn' href='" + ctx
                    + "/admin/wallets'>Back to the student list</a></p></div></main>" + foot());
            return;
        }

        out.print(head(name + " - skill badge wallet", ctx, viewer));
        out.print("<main class='wrap'>");
        out.print("<header class='page-head'>"
                + "<p class='eyebrow'><a href='" + ctx + "/admin/wallets'>&larr; All students</a></p>"
                + "<h1>" + Json.html(name) + "</h1>"
                + "<p class='sub'>" + Json.html(email) + " &middot; enrolled " + enrolled
                + " &middot; student #" + studentId + "</p></header>");

        // ---- badges held ---------------------------------------------
        StringBuilder badges = new StringBuilder();
        int badgeCount = 0;
        ps = conn.prepareStatement(SQL_BADGES);
        try {
            ps.setInt(1, studentId);
            ResultSet rs = ps.executeQuery();
            try {
                while (rs.next()) {
                    badgeCount++;
                    String code = rs.getString("verification_code");
                    String tier = rs.getString("tier");
                    boolean revoked = rs.getInt("revoked") == 1;
                    badges.append("<article class='badge tier-").append(tier.toLowerCase())
                          .append(revoked ? " revoked" : "").append("'>")
                          .append("<div class='medal'>").append(tier.charAt(0)).append("</div>")
                          .append("<div class='badge-body'>")
                          .append("<h3>").append(Json.html(badgeTitle(rs))).append("</h3>")
                          .append("<p class='unit'>").append(Json.html(badgeSubtitle(rs)))
                          .append("</p>")
                          .append("<p class='meta'><span class='pill'>").append(tier)
                          .append("</span> score ").append(rs.getBigDecimal("score"))
                          .append(" &middot; issued ").append(TimeFmt.utc(rs.getLong("issued_at_ms")))
                          .append(" UTC</p>")
                          .append(revoked ? "<p class='warn'>This badge has been revoked.</p>" : "")
                          .append("</div>")
                          .append("<div class='badge-aside'>")
                          .append("<p class='code-row'><code>").append(code).append("</code>")
                          .append(revoked ? "" : "<span class='verified-mark'>&#10003; verified</span>")
                          .append("</p>")
                          .append("<p class='code-row'>")
                          .append("<button class='btn small ghost' data-copy='").append(code)
                          .append("'>Copy</button>")
                          .append("<a class='btn small ghost' target='_blank' href='").append(ctx)
                          .append("/verify.html?code=").append(code).append("'>Verify</a></p>")
                          .append("</div></article>");
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }

        out.print("<h2 class='section'>Badges earned <span class='count'>"
                + badgeCount + "</span></h2>");
        if (badgeCount == 0) {
            out.print("<div class='card empty'><p>No badges yet. Claim one from a "
                    + "completed module below.</p></div>");
        } else {
            out.print("<div class='badges'>" + badges + "</div>");
        }

        // ---- completed but unclaimed ---------------------------------
        StringBuilder unclaimed = new StringBuilder();
        int unclaimedCount = 0;
        ps = conn.prepareStatement(SQL_UNCLAIMED);
        try {
            ps.setInt(1, studentId);
            ps.setInt(2, studentId);
            ResultSet rs = ps.executeQuery();
            try {
                while (rs.next()) {
                    unclaimedCount++;
                    int mid = rs.getInt("module_id");
                    unclaimed.append("<tr><td>").append(Json.html(rs.getString("title")))
                             .append("<span class='unit-inline'>")
                             .append(Json.html(rs.getString("unit"))).append("</span></td>")
                             .append("<td class='mono'>").append(rs.getBigDecimal("score"))
                             .append("</td><td class='mono'>")
                             .append(BadgeCode.tierFor(rs.getDouble("score")))
                             .append("</td><td><button class='btn small issue' data-student='")
                             .append(studentId).append("' data-module='").append(mid)
                             .append("'>Issue badge</button></td></tr>");
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }

        out.print("<h2 class='section'>Completed, not yet claimed <span class='count'>"
                + unclaimedCount + "</span></h2>");
        if (unclaimedCount == 0) {
            out.print("<div class='card empty'><p>Every completed module has a badge. "
                    + "Nothing left to claim.</p></div>");
        } else {
            out.print("<div class='card'><table class='grid'><thead><tr><th>Module</th>"
                    + "<th>Score</th><th>Tier</th><th></th></tr></thead><tbody>"
                    + unclaimed + "</tbody></table></div>");
        }

        out.print("</main>");
        out.print("<script>"
                + "document.addEventListener('click',function(e){"
                + " var c=e.target.closest('[data-copy]');"
                + " if(c){navigator.clipboard.writeText(c.dataset.copy);"
                + "   var t=c.textContent;c.textContent='Copied';"
                + "   setTimeout(function(){c.textContent=t;},1200);return;}"
                + " var b=e.target.closest('button.issue');"
                + " if(!b)return;"
                + " b.disabled=true;b.textContent='Issuing...';"
                + " var body='studentId='+b.dataset.student+'&moduleId='+b.dataset.module;"
                + " fetch('" + ctx + "/api/issue',{method:'POST',"
                + "  headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})"
                + "  .then(function(r){return r.json();})"
                + "  .then(function(j){ if(j.verificationCode){location.reload();}"
                + "     else {b.disabled=false;b.textContent='Failed';alert(j.message||'Issue failed');} })"
                + "  .catch(function(){b.disabled=false;b.textContent='Issue badge';});"
                + "});</script>");
        out.print(foot());
    }

    // -----------------------------------------------------------------
    /**
     * Page chrome, delegated to Layout so the wallet, the dashboards and
     * the sign-in page cannot drift apart. This class carried its own
     * private copy while it was the only server-rendered page; that did
     * not survive the arrival of four more.
     */
    /**
     * A module badge names its module; a claim badge names the claim it
     * came from. Same shape either way, so the wallet does not need to
     * know which kind it is rendering.
     */
    private static String badgeTitle(ResultSet rs) throws SQLException {
        String t = rs.getString("title");
        if (t == null) t = rs.getString("claim_title");
        return (t == null) ? "Skill badge" : t;
    }

    private static String badgeSubtitle(ResultSet rs) throws SQLException {
        if (rs.getString("title") != null) {
            String unit = rs.getString("unit");
            return (unit == null) ? "" : unit;
        }
        String issuer = rs.getString("claim_issuer");
        return (issuer == null || issuer.isEmpty())
             ? "Verified by an administrator"
             : "Issued by " + issuer + ", verified by an administrator";
    }

    /**
     * Only an administrator can reach this page, so it renders the
     * signed-in chrome: the admin nav and the session bar. Rendering the
     * public nav here would show a visitor's menu to someone who is
     * plainly not a visitor.
     */
    static String head(String title, String ctx, Accounts.Account viewer) {
        return Layout.headSignedIn(title, "wallets", viewer, ctx);
    }

    static String foot() {
        return Layout.foot();
    }
}
