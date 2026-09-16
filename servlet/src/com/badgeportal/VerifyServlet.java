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

/**
 * THE BENCHMARKED ENDPOINT (Servlet half).
 *
 *     GET /badgeportal/api/verify?code=SF-3A9F-B21C-7E04
 *
 * cgi/verify.py is the other half. The two are held to the same
 * contract: same query parameter, same SQL, same JSON keys, same status
 * vocabulary. Only the execution model differs, which is exactly what
 * makes the timing comparison meaningful.
 *
 * What happens per request here:
 *   1. Tomcat picks a thread out of its existing pool.
 *   2. This already-loaded, already-JIT-compiled instance is reused.
 *   3. A live JDBC connection is borrowed from Db.
 *   4. One indexed SELECT, one digest, one JSON write.
 *   5. The connection goes back to the pool; the thread goes back to
 *      the pool. Nothing is torn down.
 *
 * Compare with the CGI list in cgi/verify.py.
 */
public class VerifyServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /**
     * LEFT JOIN on modules and badge_claims, not INNER.
     *
     * A badge issued from an approved claim has no module_id, so an
     * inner join would fail to match it at all and this endpoint would
     * answer NOT_FOUND for a badge that genuinely exists -- the worst
     * possible failure for a verification service.
     *
     * Any change here must be mirrored exactly in cgi/verify.py. The
     * two implementations are held to identical output by the parity
     * check in bench/benchmark.py, which samples codes at random and
     * will therefore pick up claim-issued badges once they exist.
     */
    private static final String SQL =
        "SELECT b.badge_id, b.student_id, b.module_id, b.verification_code, "
      + "       b.tier, b.score, b.issued_at_ms, b.revoked, "
      + "       s.name AS student_name, s.email AS student_email, "
      + "       m.title AS module_title, m.unit AS module_unit, m.code_prefix, "
      + "       c.title AS claim_title, c.issuer AS claim_issuer "
      + "FROM badges b "
      + "JOIN      students     s ON s.student_id = b.student_id "
      + "LEFT JOIN modules      m ON m.module_id  = b.module_id "
      + "LEFT JOIN badge_claims c ON c.claim_id   = b.claim_id "
      + "WHERE b.verification_code = ?";

    /**
     * init() runs once per servlet instance, not once per request. There
     * is nothing left to do here -- the pool was opened by AppListener
     * before any request arrived -- but logging it makes the lifecycle
     * visible in the Tomcat log when you demo the project.
     */
    @Override
    public void init() throws ServletException {
        log("[badgeportal] VerifyServlet.init() -- runs ONCE for the "
          + "lifetime of the application, not per request");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        long t0 = System.nanoTime();

        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        // Lets the static Verify page fetch this endpoint from a page
        // served by Apache on a different port.
        resp.setHeader("Access-Control-Allow-Origin", "*");

        String code = BadgeCode.normalise(req.getParameter("code"));

        PrintWriter out = resp.getWriter();

        if (code.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print(new Json()
                .put("code", "")
                .put("valid", false)
                .put("status", "BAD_REQUEST")
                .put("message", "Missing required query parameter: code")
                .put("servedBy", "servlet")
                .put("elapsedMs", elapsedMs(t0))
                .end());
            return;
        }

        Connection conn = null;
        try {
            conn = Db.borrow();
            PreparedStatement ps = conn.prepareStatement(SQL);
            try {
                ps.setString(1, code);
                ResultSet rs = ps.executeQuery();
                try {
                    if (!rs.next()) {
                        resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                        out.print(new Json()
                            .put("code", code)
                            .put("valid", false)
                            .put("status", "NOT_FOUND")
                            .put("message", "No badge has ever been issued with this code.")
                            .put("servedBy", "servlet")
                            .put("elapsedMs", elapsedMs(t0))
                            .end());
                        return;
                    }

                    int    studentId  = rs.getInt("student_id");
                    long   issuedAtMs = rs.getLong("issued_at_ms");
                    String stored     = rs.getString("verification_code");
                    boolean revoked   = rs.getInt("revoked") == 1;

                    // A claim-issued badge belongs to no module, so
                    // module_id is NULL and there is no code_prefix to
                    // read. Both fall back to fixed values that the
                    // issuer used when it built the code, and that
                    // cgi/verify.py uses too. getInt() already returns
                    // 0 for NULL; saying so explicitly keeps the two
                    // implementations obviously aligned.
                    int moduleId = rs.getInt("module_id");
                    if (rs.wasNull()) moduleId = BadgeCode.NO_MODULE;

                    String prefix = rs.getString("code_prefix");
                    if (prefix == null) prefix = BadgeCode.CLAIM_PREFIX;

                    // ---- the tamper-evident check -----------------------
                    // Recompute the code the badge row SHOULD carry, from
                    // the row itself plus the signing secret. If someone
                    // has edited the database to point a code at another
                    // student, the digest will no longer line up.
                    String expected = BadgeCode.generate(
                            prefix, studentId, moduleId, issuedAtMs, Config.secret());

                    if (!BadgeCode.matches(expected, stored)) {
                        resp.setStatus(HttpServletResponse.SC_CONFLICT);
                        out.print(new Json()
                            .put("code", code)
                            .put("valid", false)
                            .put("status", "TAMPERED")
                            .put("message", "This badge record does not match its own "
                                          + "signature and cannot be trusted.")
                            .put("servedBy", "servlet")
                            .put("elapsedMs", elapsedMs(t0))
                            .end());
                        return;
                    }

                    if (revoked) {
                        resp.setStatus(HttpServletResponse.SC_GONE);
                        out.print(new Json()
                            .put("code", code)
                            .put("valid", false)
                            .put("status", "REVOKED")
                            .put("message", "This badge was issued but has since been revoked.")
                            .put("servedBy", "servlet")
                            .put("elapsedMs", elapsedMs(t0))
                            .end());
                        return;
                    }

                    String student = new Json()
                        .put("id", studentId)
                        .put("name", rs.getString("student_name"))
                        .put("email", rs.getString("student_email"))
                        .end();

                    // The response shape stays the same whichever way
                    // the badge was issued. A claim badge describes
                    // itself through the claim it came from, so an
                    // employer sees "Python for Everybody / Issued by
                    // Coursera" in the same place they would see a
                    // module and its unit. Keeping one shape means
                    // verify.html needs no special case and the parity
                    // check keeps comparing like with like.
                    String moduleTitle = rs.getString("module_title");
                    String moduleUnit  = rs.getString("module_unit");
                    if (moduleTitle == null) {
                        moduleTitle = rs.getString("claim_title");
                        String issuer = rs.getString("claim_issuer");
                        moduleUnit = (issuer == null || issuer.isEmpty())
                            ? "Verified by an administrator"
                            : "Issued by " + issuer + ", verified by an administrator";
                    }
                    if (moduleTitle == null) moduleTitle = "Skill badge";
                    if (moduleUnit == null)  moduleUnit  = "";

                    String module = new Json()
                        .put("id", moduleId)
                        .put("title", moduleTitle)
                        .put("unit", moduleUnit)
                        .end();

                    out.print(new Json()
                        .put("code", code)
                        .put("valid", true)
                        .put("status", "VALID")
                        .putRaw("student", student)
                        .putRaw("module", module)
                        .put("tier", rs.getString("tier"))
                        .put("score", rs.getDouble("score"))
                        .put("issuedAt", TimeFmt.utc(issuedAtMs))
                        .put("issuedAtMs", issuedAtMs)
                        .put("servedBy", "servlet")
                        .put("elapsedMs", elapsedMs(t0))
                        .end());
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            log("[badgeportal] verify failed for code " + code, e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print(new Json()
                .put("code", code)
                .put("valid", false)
                .put("status", "ERROR")
                .put("message", "Verification service is temporarily unavailable.")
                .put("servedBy", "servlet")
                .put("elapsedMs", elapsedMs(t0))
                .end());
        } finally {
            Db.release(conn);
        }
    }

    private static double elapsedMs(long t0) {
        return Math.round((System.nanoTime() - t0) / 1000.0) / 1000.0;
    }
}
