package com.badgeportal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Badge claims: a student's request for a credential earned elsewhere.
 *
 * A claim is never a credential. It carries no verification code and is
 * invisible to /api/verify. Only when an admin approves it does a row
 * appear in `badges` with a server-generated code -- which is what stops
 * a student awarding themselves anything.
 */
public final class Claims {

    private Claims() { }

    /** One claim, as the dashboards display it. */
    public static final class Claim {
        public int    claimId;
        public int    studentId;
        public String studentName;
        public String title;
        public String issuer;
        public String externalUrl;
        public String evidencePath;
        public String evidenceName;
        public String proposedTier;
        public String status;
        public long   submittedAtMs;
        public String reviewNote;

        public boolean isPending()  { return "PENDING".equals(status); }
        public boolean isRejected() { return "REJECTED".equals(status); }
        public boolean hasFile()    { return evidencePath != null && !evidencePath.isEmpty(); }
        public boolean hasLink()    { return externalUrl != null && !externalUrl.isEmpty(); }
    }

    private static final String COLUMNS =
        "c.claim_id, c.student_id, c.title, c.issuer, c.external_url, "
      + "c.evidence_path, c.evidence_name, c.proposed_tier, c.status, "
      + "c.submitted_at, c.review_note, s.name AS student_name ";

    private static final String SQL_BY_STUDENT =
        "SELECT " + COLUMNS
      + "FROM badge_claims c JOIN students s ON s.student_id = c.student_id "
      + "WHERE c.student_id = ? ORDER BY c.submitted_at DESC";

    private static final String SQL_BY_ID =
        "SELECT " + COLUMNS
      + "FROM badge_claims c JOIN students s ON s.student_id = c.student_id "
      + "WHERE c.claim_id = ?";

    private static final String SQL_INSERT =
        "INSERT INTO badge_claims (student_id, title, issuer, external_url, "
      + "  evidence_path, evidence_name, proposed_tier, status, submitted_at) "
      + "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)";

    /** Every claim this student has ever made, newest first. */
    public static List<Claim> forStudent(int studentId) throws SQLException {
        List<Claim> out = new ArrayList<Claim>();
        Connection conn = null;
        try {
            conn = Db.borrow();
            PreparedStatement ps = conn.prepareStatement(SQL_BY_STUDENT);
            try {
                ps.setInt(1, studentId);
                ResultSet rs = ps.executeQuery();
                try {
                    while (rs.next()) out.add(read(rs));
                } finally { rs.close(); }
            } finally { ps.close(); }
        } finally {
            Db.release(conn);
        }
        return out;
    }

    /** A single claim, or null. */
    public static Claim byId(int claimId) throws SQLException {
        Connection conn = null;
        try {
            conn = Db.borrow();
            PreparedStatement ps = conn.prepareStatement(SQL_BY_ID);
            try {
                ps.setInt(1, claimId);
                ResultSet rs = ps.executeQuery();
                try {
                    return rs.next() ? read(rs) : null;
                } finally { rs.close(); }
            } finally { ps.close(); }
        } finally {
            Db.release(conn);
        }
    }

    /** Files a new claim. Returns its id. */
    public static int submit(int studentId, String title, String issuer,
                             String externalUrl, String evidencePath,
                             String evidenceName, String tier)
            throws SQLException {
        Connection conn = null;
        try {
            conn = Db.borrow();
            PreparedStatement ps = conn.prepareStatement(
                SQL_INSERT, java.sql.Statement.RETURN_GENERATED_KEYS);
            try {
                ps.setInt(1, studentId);
                ps.setString(2, title);
                setOrNull(ps, 3, issuer);
                setOrNull(ps, 4, externalUrl);
                setOrNull(ps, 5, evidencePath);
                setOrNull(ps, 6, evidenceName);
                ps.setString(7, tier);
                ps.setTimestamp(8, new Timestamp(System.currentTimeMillis()));
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                try {
                    return keys.next() ? keys.getInt(1) : 0;
                } finally { keys.close(); }
            } finally { ps.close(); }
        } finally {
            Db.release(conn);
        }
    }

    private static Claim read(ResultSet rs) throws SQLException {
        Claim c = new Claim();
        c.claimId       = rs.getInt("claim_id");
        c.studentId     = rs.getInt("student_id");
        c.studentName   = rs.getString("student_name");
        c.title         = rs.getString("title");
        c.issuer        = rs.getString("issuer");
        c.externalUrl   = rs.getString("external_url");
        c.evidencePath  = rs.getString("evidence_path");
        c.evidenceName  = rs.getString("evidence_name");
        c.proposedTier  = rs.getString("proposed_tier");
        c.status        = rs.getString("status");
        c.reviewNote    = rs.getString("review_note");
        Timestamp t     = rs.getTimestamp("submitted_at");
        c.submittedAtMs = (t == null) ? 0L : t.getTime();
        return c;
    }

    private static void setOrNull(PreparedStatement ps, int i, String v)
            throws SQLException {
        if (v == null || v.trim().isEmpty()) ps.setNull(i, java.sql.Types.VARCHAR);
        else ps.setString(i, v.trim());
    }
}
