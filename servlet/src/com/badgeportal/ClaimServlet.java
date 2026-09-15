package com.badgeportal;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;

/**
 * Submitting a badge claim.
 *
 *     POST /student/claim
 *
 * Sits behind AuthFilter, so a session exists and belongs to a student
 * by the time this runs. The student id is taken from that session and
 * never from the request: a posted studentId field would let anyone file
 * a claim in somebody else's name.
 *
 * Redirects back to the dashboard rather than rendering, so a refresh
 * cannot resubmit the same claim.
 */
public class ClaimServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final int MAX_TITLE  = 160;
    private static final int MAX_ISSUER = 120;
    private static final int MAX_URL    = 500;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException, ServletException {

        Accounts.Account account = Auth.current(req);
        String ctx = req.getContextPath();
        String dashboard = ctx + "/student/dashboard";

        if (!Auth.csrfValid(req)) {
            // Either a cross-site post or a session that expired while
            // the form sat open. Neither should be silently accepted.
            resp.sendRedirect(dashboard + "?error=" + enc(
                "Your session expired while the form was open. Please try again."));
            return;
        }

        String title  = trim(req.getParameter("title"),  MAX_TITLE);
        String issuer = trim(req.getParameter("issuer"), MAX_ISSUER);
        String url    = trim(req.getParameter("externalUrl"), MAX_URL);
        String tier   = tierOf(req.getParameter("proposedTier"));

        if (title.isEmpty()) {
            resp.sendRedirect(dashboard + "?error=" + enc("Give the badge a title."));
            return;
        }

        if (!url.isEmpty() && !isHttpUrl(url)) {
            resp.sendRedirect(dashboard + "?error=" + enc(
                "The link must be a full http:// or https:// address."));
            return;
        }

        // Evidence is optional, but a claim with neither a link nor a
        // file gives an admin nothing to verify against.
        //
        // getPart() throws if the request is not multipart, and the
        // form always is -- but a client that posts plain form-encoded
        // data is a request to handle, not a stack trace to emit. Check
        // the content type first and treat anything else as "no file".
        Uploads.Stored stored;
        try {
            String ctype = req.getContentType();
            boolean isMultipart = ctype != null
                && ctype.toLowerCase().startsWith("multipart/");
            Part part = isMultipart ? req.getPart("evidence") : null;
            stored = Uploads.accept(part);
        } catch (Uploads.RejectedException e) {
            resp.sendRedirect(dashboard + "?error=" + enc(e.getMessage()));
            return;
        } catch (IllegalStateException e) {
            // The container rejected the request for exceeding its own
            // multipart limits before we ever saw the bytes.
            resp.sendRedirect(dashboard + "?error=" + enc(
                "That file is too large. The limit is "
                + (Config.uploadMaxBytes() / (1024 * 1024)) + " MB."));
            return;
        }

        if (url.isEmpty() && stored == null) {
            resp.sendRedirect(dashboard + "?error=" + enc(
                "Add a link or upload a certificate, so it can be verified."));
            return;
        }

        try {
            Claims.submit(account.studentId, title, issuer, url,
                          stored == null ? null : stored.storedName,
                          stored == null ? null : stored.originalName,
                          tier);
        } catch (SQLException e) {
            log("[badgeportal] claim submission failed for student "
                + account.studentId, e);
            resp.sendRedirect(dashboard + "?error=" + enc(
                "Could not save your submission. Please try again."));
            return;
        }

        resp.sendRedirect(dashboard + "?submitted=1");
    }

    /** A GET here is somebody typing the URL; send them somewhere useful. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.sendRedirect(req.getContextPath() + "/student/dashboard");
    }

    // -----------------------------------------------------------------
    private static boolean isHttpUrl(String s) {
        try {
            URI u = new URI(s);
            String scheme = u.getScheme();
            return u.isAbsolute()
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && u.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String tierOf(String raw) {
        if ("GOLD".equalsIgnoreCase(raw))   return "GOLD";
        if ("SILVER".equalsIgnoreCase(raw)) return "SILVER";
        return "BRONZE";
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            return "Submission failed";
        }
    }
}
