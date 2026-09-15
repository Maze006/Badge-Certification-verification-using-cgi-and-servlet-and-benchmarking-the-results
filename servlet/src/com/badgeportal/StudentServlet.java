package com.badgeportal;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Student dashboard.
 *
 *     GET /student/dashboard
 *
 * Shows the badges this student holds, the claims they have submitted
 * and where each one stands, and the form for submitting another.
 *
 * It never reads a student id from the request. The id comes from the
 * session, so a student cannot read another student's dashboard by
 * editing a URL. (/wallet still takes ?studentId= because it is a public
 * directory of issued credentials, which are public by design -- but
 * claims and evidence are not, and they live only here.)
 */
public class StudentServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /**
     * LEFT JOIN, not INNER. A badge issued from an approved claim has no
     * module_id, and an inner join would silently hide exactly the
     * badges this page exists to celebrate.
     */
    private static final String SQL_BADGES =
        "SELECT b.verification_code, b.tier, b.score, b.issued_at_ms, b.revoked, "
      + "       b.source, m.title AS module_title, m.unit AS module_unit, "
      + "       c.title AS claim_title, c.issuer AS claim_issuer "
      + "FROM badges b "
      + "LEFT JOIN modules      m ON m.module_id = b.module_id "
      + "LEFT JOIN badge_claims c ON c.claim_id  = b.claim_id "
      + "WHERE b.student_id = ? ORDER BY b.issued_at_ms DESC";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        Accounts.Account account = Auth.current(req);
        String ctx = req.getContextPath();

        resp.setContentType("text/html;charset=UTF-8");

        StringBuilder badges = new StringBuilder();
        int badgeCount = 0;
        List<Claims.Claim> claims = null;

        Connection conn = null;
        try {
            conn = Db.borrow();
            PreparedStatement ps = conn.prepareStatement(SQL_BADGES);
            try {
                ps.setInt(1, account.studentId);
                ResultSet rs = ps.executeQuery();
                try {
                    while (rs.next()) {
                        badgeCount++;
                        badges.append(renderBadge(rs, ctx));
                    }
                } finally { rs.close(); }
            } finally { ps.close(); }
        } catch (SQLException e) {
            log("[badgeportal] student dashboard failed for account "
                + account.accountId, e);
        } finally {
            Db.release(conn);
        }

        try {
            claims = Claims.forStudent(account.studentId);
        } catch (SQLException e) {
            log("[badgeportal] claim list failed for student "
                + account.studentId, e);
        }

        int pending = 0;
        if (claims != null) {
            for (Claims.Claim c : claims) if (c.isPending()) pending++;
        }

        StringBuilder out = new StringBuilder(8000);
        out.append(Layout.headSignedIn("Student dashboard", "dashboard", account, ctx))
           .append("<main class='wrap'>")
           .append("<header class='page-head'><h1>")
           .append(Json.html(account.displayName)).append("</h1>")
           .append("<p class='sub'>").append(Json.html(account.email))
           .append("</p></header>");

        out.append(banner(req));

        // ---- submission form -----------------------------------------
        out.append("<h2 class='section'>Upload a badge</h2>")
           .append("<div class='card'>")
           .append("<p class='sub' style='margin-bottom:20px'>")
           .append("Earned a certificate elsewhere? Submit it here. An ")
           .append("administrator verifies it before a verification code ")
           .append("is issued &mdash; which is what makes the code worth ")
           .append("anything.</p>")
           .append("<form method='post' enctype='multipart/form-data' action='")
           .append(ctx).append("/student/claim'>")
           .append(Auth.csrfField(req))

           .append("<div class='field'><label for='title'>Badge title</label>")
           .append("<input type='text' id='title' name='title' required ")
           .append("maxlength='160' placeholder='Responsive Web Design'></div>")

           .append("<div class='field-row'>")
           .append("<div class='field'><label for='issuer'>Issued by</label>")
           .append("<input type='text' id='issuer' name='issuer' maxlength='120' ")
           .append("placeholder='Coursera'></div>")
           .append("<div class='field'><label for='proposedTier'>Tier you are claiming</label>")
           .append("<select id='proposedTier' name='proposedTier'>")
           .append("<option value='BRONZE'>Bronze</option>")
           .append("<option value='SILVER'>Silver</option>")
           .append("<option value='GOLD'>Gold</option>")
           .append("</select></div>")
           .append("</div>")

           .append("<div class='field'><label for='externalUrl'>Verification link</label>")
           .append("<input type='url' id='externalUrl' name='externalUrl' ")
           .append("maxlength='500' placeholder='https://coursera.org/verify/ABC123'>")
           .append("<p class='field-hint'>The page the issuer publishes for this ")
           .append("certificate.</p></div>")

           .append("<div class='field'><label for='evidence'>Certificate file</label>")
           .append("<input type='file' id='evidence' name='evidence' ")
           .append("accept='.pdf,.png,.jpg,.jpeg'>")
           .append("<p class='field-hint'>PDF, PNG or JPG, up to ")
           .append(Config.uploadMaxBytes() / (1024 * 1024))
           .append("&nbsp;MB. A link or a file is required &mdash; both is better.")
           .append("</p></div>")

           .append("<button class='btn' type='submit'>Submit for verification</button>")
           .append("</form></div>");

        // ---- claims --------------------------------------------------
        out.append("<h2 class='section'>Your submissions <span class='count'>")
           .append(claims == null ? 0 : claims.size()).append("</span>");
        if (pending > 0) {
            out.append("<span class='count'>").append(pending).append(" pending</span>");
        }
        out.append("</h2>");

        if (claims == null || claims.isEmpty()) {
            out.append("<div class='card empty'><p>Nothing submitted yet.</p></div>");
        } else {
            out.append("<div class='card'><table class='grid'><thead><tr>")
               .append("<th>Badge</th><th>Tier</th><th>Evidence</th>")
               .append("<th>Submitted</th><th>Status</th></tr></thead><tbody>");
            for (Claims.Claim c : claims) {
                out.append(renderClaimRow(c, ctx));
            }
            out.append("</tbody></table></div>");
        }

        // ---- badges held ---------------------------------------------
        out.append("<h2 class='section'>Badges earned <span class='count'>")
           .append(badgeCount).append("</span></h2>");
        if (badgeCount == 0) {
            out.append("<div class='card empty'><p>No badges yet.</p></div>");
        } else {
            out.append("<div class='badges'>").append(badges).append("</div>");
        }

        out.append("</main>")
           .append(copyScript())
           .append(Layout.foot());

        resp.getWriter().print(out);
    }

    // -----------------------------------------------------------------
    private String renderBadge(ResultSet rs, String ctx) throws SQLException {
        String code = rs.getString("verification_code");
        String tier = rs.getString("tier");
        boolean revoked = rs.getInt("revoked") == 1;

        // A module badge names its module; a claim badge names the claim.
        String title = rs.getString("module_title");
        String sub   = rs.getString("module_unit");
        if (title == null) {
            title = rs.getString("claim_title");
            String issuer = rs.getString("claim_issuer");
            sub = (issuer == null || issuer.isEmpty())
                ? "Verified by an administrator"
                : "Issued by " + issuer + ", verified by an administrator";
        }
        if (title == null) title = "Badge";
        if (sub == null) sub = "";

        StringBuilder b = new StringBuilder(700);
        b.append("<article class='badge tier-").append(tier.toLowerCase())
         .append(revoked ? " revoked" : "").append("'>")
         .append("<div class='medal'>").append(tier.charAt(0)).append("</div>")
         .append("<div class='badge-body'>")
         .append("<h3>").append(Json.html(title)).append("</h3>")
         .append("<p class='unit'>").append(Json.html(sub)).append("</p>")
         .append("<p class='meta'><span class='pill'>").append(tier)
         .append("</span> score ").append(rs.getBigDecimal("score"))
         .append(" &middot; issued ")
         .append(TimeFmt.utc(rs.getLong("issued_at_ms"))).append(" UTC</p>")
         .append(revoked ? "<p class='warn'>This badge has been revoked.</p>" : "")
         .append("</div>")
         .append("<div class='badge-aside'>")
         .append("<p class='code-row'><code>").append(code).append("</code>")
         .append(revoked ? "" : "<span class='verified-mark'>&#10003; verified</span>")
         .append("</p><p class='code-row'>")
         .append("<button class='btn small ghost' data-copy='").append(code)
         .append("'>Copy</button>")
         .append("<a class='btn small ghost' target='_blank' href='").append(ctx)
         .append("/verify.html?code=").append(code).append("'>Verify</a></p>")
         .append("</div></article>");
        return b.toString();
    }

    private String renderClaimRow(Claims.Claim c, String ctx) {
        StringBuilder b = new StringBuilder(600);
        b.append("<tr><td>").append(Json.html(c.title));
        if (c.issuer != null && !c.issuer.isEmpty()) {
            b.append("<span class='unit-inline'>").append(Json.html(c.issuer))
             .append("</span>");
        }
        if (c.isRejected() && c.reviewNote != null && !c.reviewNote.isEmpty()) {
            b.append("<span class='unit-inline review-note'>")
             .append(Json.html(c.reviewNote)).append("</span>");
        }
        b.append("</td>")
         .append("<td class='mono'>").append(Json.html(c.proposedTier)).append("</td>")
         .append("<td>");
        if (c.hasLink()) {
            b.append("<a class='btn small ghost' target='_blank' rel='noopener' href='")
             .append(Json.html(c.externalUrl)).append("'>link</a> ");
        }
        if (c.hasFile()) {
            b.append("<a class='btn small ghost' target='_blank' href='").append(ctx)
             .append("/files/evidence?claim=").append(c.claimId).append("'>file</a>");
        }
        b.append("</td>")
         .append("<td class='mono'>").append(TimeFmt.utc(c.submittedAtMs)).append("</td>")
         .append("<td><span class='status status-").append(c.status.toLowerCase())
         .append("'>").append(Json.html(c.status)).append("</span></td></tr>");
        return b.toString();
    }

    /** Success and error banners, driven by the redirect query string. */
    private String banner(HttpServletRequest req) {
        if (req.getParameter("submitted") != null) {
            return "<p class='form-note' role='status'>&#10003; Submitted. "
                 + "An administrator will verify it.</p>";
        }
        String error = req.getParameter("error");
        if (error != null && !error.isEmpty()) {
            return "<p class='form-error' role='alert'>&#10007; "
                 + Json.html(error) + "</p>";
        }
        return "";
    }

    private String copyScript() {
        return "<script>document.addEventListener('click',function(e){"
             + "var c=e.target.closest&&e.target.closest('[data-copy]');"
             + "if(!c)return;navigator.clipboard.writeText(c.dataset.copy);"
             + "var t=c.textContent;c.textContent='Copied';"
             + "setTimeout(function(){c.textContent=t;},1200);});</script>";
    }
}
