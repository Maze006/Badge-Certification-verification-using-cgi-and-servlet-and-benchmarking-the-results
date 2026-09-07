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
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;

/**
 * Badge issuance.
 *
 *     POST /badgeportal/api/issue      (studentId, moduleId)
 *     GET  /badgeportal/api/issue?...  (same, allowed for demo convenience)
 *
 * Issuance is the write side of the portal and is implemented ONLY as a
 * servlet -- the PBL asks for the verification lookup to exist twice,
 * not the whole application. That is also how real credentialing
 * platforms are shaped: issuance is a low-volume, transactional path,
 * while verification is the high-volume public one.
 *
 * The operation is idempotent. Re-issuing a badge a student already
 * holds returns the existing badge rather than minting a second code,
 * which is enforced at the database level too by the unique key on
 * (student_id, module_id).
 */
public class IssueServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final String SQL_COMPLETION =
        "SELECT c.score, m.code_prefix, m.title, m.unit, s.name, s.email "
      + "FROM module_completions c "
      + "JOIN modules  m ON m.module_id  = c.module_id "
      + "JOIN students s ON s.student_id = c.student_id "
      + "WHERE c.student_id = ? AND c.module_id = ?";

    private static final String SQL_EXISTING =
        "SELECT verification_code, tier, score, issued_at_ms "
      + "FROM badges WHERE student_id = ? AND module_id = ?";

    private static final String SQL_INSERT =
        "INSERT INTO badges (student_id, module_id, verification_code, tier, "
      + "                    score, issued_at_ms, issued_at) "
      + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        handle(req, resp);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        handle(req, resp);
    }

    private void handle(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        PrintWriter out = resp.getWriter();

        int studentId = intParam(req, "studentId");
        int moduleId  = intParam(req, "moduleId");

        if (studentId <= 0 || moduleId <= 0) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print(new Json()
                .put("issued", false)
                .put("status", "BAD_REQUEST")
                .put("message", "studentId and moduleId are required positive integers")
                .end());
            return;
        }

        Connection conn = null;
        try {
            conn = Db.borrow();

            // --- 1. has the student actually completed the module? -------
            double score;
            String prefix, moduleTitle, moduleUnit, studentName, studentEmail;

            PreparedStatement ps = conn.prepareStatement(SQL_COMPLETION);
            try {
                ps.setInt(1, studentId);
                ps.setInt(2, moduleId);
                ResultSet rs = ps.executeQuery();
                try {
                    if (!rs.next()) {
                        resp.setStatus(HttpServletResponse.SC_CONFLICT);
                        out.print(new Json()
                            .put("issued", false)
                            .put("status", "NOT_COMPLETED")
                            .put("message", "No completion record for student "
                                          + studentId + " on module " + moduleId
                                          + ". A badge cannot be issued for work "
                                          + "that has not been completed.")
                            .end());
                        return;
                    }
                    score        = rs.getDouble("score");
                    prefix       = rs.getString("code_prefix");
                    moduleTitle  = rs.getString("title");
                    moduleUnit   = rs.getString("unit");
                    studentName  = rs.getString("name");
                    studentEmail = rs.getString("email");
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
            }

            // --- 2. already issued? then hand back the same badge --------
            ps = conn.prepareStatement(SQL_EXISTING);
            try {
                ps.setInt(1, studentId);
                ps.setInt(2, moduleId);
                ResultSet rs = ps.executeQuery();
                try {
                    if (rs.next()) {
                        out.print(badgeJson(false, "ALREADY_ISSUED",
                                rs.getString("verification_code"),
                                rs.getString("tier"),
                                rs.getDouble("score"),
                                rs.getLong("issued_at_ms"),
                                studentId, studentName, studentEmail,
                                moduleId, moduleTitle, moduleUnit));
                        return;
                    }
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
            }

            // --- 3. mint a new badge -------------------------------------
            String tier = BadgeCode.tierFor(score);
            long issuedAtMs = System.currentTimeMillis();
            String code = null;

            // The 12 hex digits give roughly 2.8e14 distinct codes, so a
            // collision is vanishingly unlikely -- but the unique index
            // is the real guarantee, and nudging the timestamp gives a
            // fresh digest if the improbable ever happens.
            for (int attempt = 0; attempt < 5 && code == null; attempt++) {
                String candidate = BadgeCode.generate(
                        prefix, studentId, moduleId, issuedAtMs, Config.secret());
                ps = conn.prepareStatement(SQL_INSERT);
                try {
                    ps.setInt(1, studentId);
                    ps.setInt(2, moduleId);
                    ps.setString(3, candidate);
                    ps.setString(4, tier);
                    ps.setDouble(5, score);
                    ps.setLong(6, issuedAtMs);
                    ps.setTimestamp(7, new Timestamp(issuedAtMs));
                    ps.executeUpdate();
                    code = candidate;
                } catch (SQLIntegrityConstraintViolationException dup) {
                    // Either a code collision, or another thread issued
                    // this exact badge a moment ago. Re-read and return.
                    if (dup.getMessage() != null
                            && dup.getMessage().contains("uq_badge_student_module")) {
                        out.print(reReadExisting(conn, studentId, moduleId,
                                studentName, studentEmail,
                                moduleTitle, moduleUnit));
                        return;
                    }
                    issuedAtMs++;
                } finally {
                    ps.close();
                }
            }

            if (code == null) {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                out.print(new Json()
                    .put("issued", false)
                    .put("status", "ERROR")
                    .put("message", "Could not allocate a unique verification code.")
                    .end());
                return;
            }

            resp.setStatus(HttpServletResponse.SC_CREATED);
            out.print(badgeJson(true, "ISSUED", code, tier, score, issuedAtMs,
                    studentId, studentName, studentEmail,
                    moduleId, moduleTitle, moduleUnit));

        } catch (SQLException e) {
            log("[badgeportal] issue failed for student " + studentId
              + " module " + moduleId, e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print(new Json()
                .put("issued", false)
                .put("status", "ERROR")
                .put("message", "Issuance service is temporarily unavailable.")
                .end());
        } finally {
            Db.release(conn);
        }
    }

    private String reReadExisting(Connection conn, int studentId, int moduleId,
                                  String studentName, String studentEmail,
                                  String moduleTitle, String moduleUnit)
            throws SQLException {
        PreparedStatement ps = conn.prepareStatement(SQL_EXISTING);
        try {
            ps.setInt(1, studentId);
            ps.setInt(2, moduleId);
            ResultSet rs = ps.executeQuery();
            try {
                if (rs.next()) {
                    return badgeJson(false, "ALREADY_ISSUED",
                            rs.getString("verification_code"),
                            rs.getString("tier"),
                            rs.getDouble("score"),
                            rs.getLong("issued_at_ms"),
                            studentId, studentName, studentEmail,
                            moduleId, moduleTitle, moduleUnit);
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
        return new Json().put("issued", false).put("status", "ERROR")
                         .put("message", "Badge state could not be read back.").end();
    }

    private String badgeJson(boolean issued, String status, String code, String tier,
                             double score, long issuedAtMs,
                             int studentId, String studentName, String studentEmail,
                             int moduleId, String moduleTitle, String moduleUnit) {
        String student = new Json()
            .put("id", studentId).put("name", studentName).put("email", studentEmail).end();
        String module = new Json()
            .put("id", moduleId).put("title", moduleTitle).put("unit", moduleUnit).end();
        return new Json()
            .put("issued", issued)
            .put("status", status)
            .put("verificationCode", code)
            .putRaw("student", student)
            .putRaw("module", module)
            .put("tier", tier)
            .put("score", score)
            .put("issuedAt", TimeFmt.utc(issuedAtMs))
            .put("issuedAtMs", issuedAtMs)
            .put("verifyUrl", "/badgeportal/verify.html?code=" + code)
            .end();
    }

    private static int intParam(HttpServletRequest req, String name) {
        try {
            return Integer.parseInt(req.getParameter(name).trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
