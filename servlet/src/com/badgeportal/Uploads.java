package com.badgeportal;

import javax.servlet.http.Part;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Accepting an uploaded certificate safely.
 *
 * File upload is the one genuinely dangerous feature in this
 * application, so the rules are collected here rather than scattered
 * through the servlet:
 *
 *   1. Stored OUTSIDE the web application, so Tomcat never serves an
 *      uploaded file as a static resource. Reaching one goes through
 *      EvidenceServlet, which checks who is asking.
 *   2. The name the browser supplied is NEVER used on disk. It is kept
 *      in the database for display only. A supplied name can contain
 *      "../" and walk out of the upload directory, or collide with
 *      another student's file, or carry a second extension.
 *   3. The extension must be on a short allowlist -- not a blocklist,
 *      which is always incomplete.
 *   4. The CONTENT is checked, not just the extension. A file named
 *      .pdf whose bytes are HTML is rejected, because a browser that
 *      sniffs it could execute script in this application's origin.
 *   5. A size cap, enforced by the container AND re-checked here.
 */
public final class Uploads {

    /** Short allowlist. Anything not named here is refused. */
    private static final String[] ALLOWED = { "pdf", "png", "jpg", "jpeg" };

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Uploads() { }

    /** Raised when an upload is refused; the message is shown to the student. */
    public static class RejectedException extends Exception {
        private static final long serialVersionUID = 1L;
        public RejectedException(String message) { super(message); }
    }

    /** The outcome of accepting a file. */
    public static final class Stored {
        public final String storedName;   // what is on disk
        public final String originalName; // what to show the student
        Stored(String storedName, String originalName) {
            this.storedName = storedName;
            this.originalName = originalName;
        }
    }

    /**
     * Validates and writes an uploaded part.
     *
     * @return null if no file was supplied (evidence is optional).
     */
    public static Stored accept(Part part) throws RejectedException, IOException {
        if (part == null || part.getSize() <= 0) return null;

        String submitted = submittedFileName(part);
        if (submitted == null || submitted.trim().isEmpty()) return null;

        if (part.getSize() > Config.uploadMaxBytes()) {
            throw new RejectedException("That file is larger than "
                + (Config.uploadMaxBytes() / (1024 * 1024)) + " MB.");
        }

        String ext = extensionOf(submitted);
        if (!isAllowed(ext)) {
            throw new RejectedException(
                "Only PDF, PNG and JPG certificates are accepted.");
        }

        // Read the leading bytes and confirm they match the claimed type.
        byte[] head = new byte[12];
        int read;
        InputStream in = part.getInputStream();
        try {
            read = readFully(in, head);
        } finally {
            in.close();
        }
        if (!contentMatches(ext, head, read)) {
            throw new RejectedException(
                "That file does not look like a real " + ext.toUpperCase(Locale.ROOT)
              + ". Check you uploaded the right thing.");
        }

        String storedName = randomHex(16) + "." + ext;

        File dir = new File(Config.uploadDir());
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Upload directory unavailable: " + dir);
        }

        File target = new File(dir, storedName);
        // Belt and braces: the name is ours and contains only hex and one
        // dot, but confirm it really did land inside the directory.
        if (!target.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
            throw new IOException("Refusing to write outside the upload directory");
        }

        InputStream src = part.getInputStream();
        OutputStream dst = new FileOutputStream(target);
        try {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = src.read(buf)) > 0) {
                total += n;
                if (total > Config.uploadMaxBytes()) {
                    dst.close();
                    target.delete();
                    throw new RejectedException("That file is too large.");
                }
                dst.write(buf, 0, n);
            }
        } finally {
            try { dst.close(); } finally { src.close(); }
        }

        return new Stored(storedName, safeDisplayName(submitted));
    }

    /** Resolves a stored name to a file, refusing anything path-like. */
    public static File resolve(String storedName) throws IOException {
        if (storedName == null || storedName.isEmpty()) return null;
        // Stored names are generated by this class: hex plus one
        // extension. Anything else did not come from here.
        if (!storedName.matches("[0-9a-f]{32}\\.[a-z]{3,4}")) return null;

        File dir = new File(Config.uploadDir());
        File f = new File(dir, storedName);
        if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
            return null;
        }
        return f.isFile() ? f : null;
    }

    /** The media type to serve a stored file as. */
    public static String contentTypeOf(String storedName) {
        String ext = extensionOf(storedName);
        if ("pdf".equals(ext))  return "application/pdf";
        if ("png".equals(ext))  return "image/png";
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
        return "application/octet-stream";
    }

    // -----------------------------------------------------------------
    private static boolean contentMatches(String ext, byte[] head, int read) {
        if (read < 4) return false;
        if ("pdf".equals(ext)) {
            return head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
        }
        if ("png".equals(ext)) {
            return (head[0] & 0xFF) == 0x89 && head[1] == 'P'
                && head[2] == 'N' && head[3] == 'G';
        }
        if ("jpg".equals(ext) || "jpeg".equals(ext)) {
            return (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8
                && (head[2] & 0xFF) == 0xFF;
        }
        return false;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    /** The browser-supplied filename, from the Content-Disposition header. */
    private static String submittedFileName(Part part) {
        String header = part.getHeader("content-disposition");
        if (header == null) return null;
        for (String token : header.split(";")) {
            token = token.trim();
            if (token.startsWith("filename")) {
                int eq = token.indexOf('=');
                if (eq < 0) continue;
                String name = token.substring(eq + 1).trim();
                if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
                    name = name.substring(1, name.length() - 1);
                }
                // Strip any directory component the browser sent.
                int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                return (slash >= 0) ? name.substring(slash + 1) : name;
            }
        }
        return null;
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isAllowed(String ext) {
        for (String a : ALLOWED) if (a.equals(ext)) return true;
        return false;
    }

    /** Trims a display name to something safe and short to render. */
    private static String safeDisplayName(String name) {
        String cleaned = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120);
        return cleaned.isEmpty() ? "certificate" : cleaned;
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte x : b) {
            sb.append(HEX[(x >> 4) & 0xF]).append(HEX[x & 0xF]);
        }
        return sb.toString();
    }
}
