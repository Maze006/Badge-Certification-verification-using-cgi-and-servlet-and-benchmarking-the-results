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

    private static final String SQL =
        "SELECT b.badge_id, b.student_id, b.module_id, b.verification_code, "
      + "       b.tier, b.score, b.issued_at_ms, b.revoked, "
      + "       s.name AS student_name, s.email AS student_email, "
      + "       m.title AS module_title, m.unit AS module_unit, m.code_prefix "
      + "FROM badges b "
      + "JOIN students s ON s.student_id = b.student_id "
      + "JOIN modules  m ON m.module_id  = b.module_id "
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
                    int    moduleId   = rs.getInt("module_id");
                    long   issuedAtMs = rs.getLong("issued_at_ms");
                    String prefix     = rs.getString("code_prefix");
                    String stored     = rs.getString("verification_code");
                    boolean revoked   = rs.getInt("revoked") == 1;

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

                    String module = new Json()
                        .put("id", moduleId)
                        .put("title", rs.getString("module_title"))
                        .put("unit", rs.getString("module_unit"))
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
