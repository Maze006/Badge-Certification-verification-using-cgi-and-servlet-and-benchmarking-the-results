package com.badgeportal;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;

/**
 * Serves an uploaded certificate, to the people entitled to see it.
 *
 *     GET /files/evidence?claim=12
 *
 * Uploaded files live outside the web application precisely so that
 * Tomcat will not serve them, which makes this the only way to reach
 * one -- and this checks who is asking:
 *
 *     a student   may read the evidence on their OWN claims
 *     an admin    may read any, because reviewing is the job
 *     anyone else gets 404
 *
 * The claim id is the handle, never the filename. If the stored name
 * were the parameter, knowing or guessing one would be enough, and the
 * ownership check would have nothing to check against.
 *
 * A claim that exists but belongs to someone else returns 404 rather
 * than 403. 403 would confirm that a particular claim id exists, which
 * is a small leak, and there is no legitimate reason for one student to
 * learn about another student's claims at all.
 */
public class EvidenceServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        Accounts.Account account = Auth.current(req);
        if (account == null) {                 // AuthFilter should have caught it
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        int claimId;
        try {
            claimId = Integer.parseInt(req.getParameter("claim").trim());
        } catch (Exception e) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        Claims.Claim claim;
        try {
            claim = Claims.byId(claimId);
        } catch (SQLException e) {
            log("[badgeportal] evidence lookup failed for claim " + claimId, e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        if (claim == null || !claim.hasFile()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        boolean mayRead = account.isAdmin() || claim.studentId == account.studentId;
        if (!mayRead) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        File file = Uploads.resolve(claim.evidencePath);
        if (file == null) {
            log("[badgeportal] evidence missing on disk for claim " + claimId);
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        resp.setContentType(Uploads.contentTypeOf(claim.evidencePath));
        resp.setContentLengthLong(file.length());
        resp.setHeader("Cache-Control", "private, no-store");

        // inline so a PDF or image opens in the browser, but with an
        // explicit filename for anyone who saves it. X-Content-Type-
        // Options stops a browser second-guessing the declared type.
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Content-Disposition",
            "inline; filename=\"" + asciiFilename(claim.evidenceName) + "\"");

        InputStream in = new FileInputStream(file);
        OutputStream out = resp.getOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
    }

    /**
     * Header values must not carry quotes, control characters or
     * non-ASCII, any of which could break out of the header.
     */
    private static String asciiFilename(String name) {
        if (name == null || name.isEmpty()) return "certificate";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 32 && c < 127 && c != '"' && c != '\\') sb.append(c);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "certificate" : out;
    }
}
