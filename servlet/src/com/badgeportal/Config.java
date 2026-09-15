package com.badgeportal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads badgeportal.properties -- the single configuration file shared
 * by the Servlet implementation and the CGI implementation.
 *
 * Resolution order:
 *   1. the BADGEPORTAL_CONFIG environment variable, if set
 *   2. /WEB-INF/badgeportal.properties inside the deployed web app
 *   3. built-in defaults (so the app still starts on a fresh checkout)
 */
public final class Config {

    private static final Properties PROPS = new Properties();
    private static String source = "built-in defaults";

    private Config() { }

    /** Called once by AppListener when the container starts the app. */
    static synchronized void load(InputStream webInfStream) {
        PROPS.clear();

        String envPath = System.getenv("BADGEPORTAL_CONFIG");
        if (envPath != null && !envPath.trim().isEmpty()) {
            try (InputStream in = new java.io.FileInputStream(envPath.trim())) {
                PROPS.load(in);
                source = "BADGEPORTAL_CONFIG=" + envPath.trim();
                return;
            } catch (IOException ignored) {
                // fall through to the packaged copy
            }
        }

        if (webInfStream != null) {
            try (InputStream in = webInfStream) {
                PROPS.load(in);
                source = "/WEB-INF/badgeportal.properties";
                return;
            } catch (IOException ignored) {
                // fall through to defaults
            }
        }

        source = "built-in defaults";
    }

    public static String configSource() {
        return source;
    }

    public static String get(String key, String fallback) {
        String v = PROPS.getProperty(key);
        return (v == null || v.trim().isEmpty()) ? fallback : v.trim();
    }

    public static int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String jdbcUrl() {
        return "jdbc:mysql://" + get("db.host", "localhost")
             + ":" + get("db.port", "3306")
             + "/" + get("db.name", "badgeportal")
             + "?useSSL=false&allowPublicKeyRetrieval=true"
             + "&serverTimezone=UTC&characterEncoding=utf8";
    }

    /**
     * Where uploaded certificates are written.
     *
     * Deliberately OUTSIDE the deployed web application. Anything under
     * webapps/badgeportal/ is served by Tomcat as a static file, so
     * storing evidence there would publish every student's certificate
     * to anyone who could guess a filename. Files here are reachable
     * only through EvidenceServlet, which checks who is asking.
     */
    public static String uploadDir() {
        return get("upload.dir", "C:/xampp/badgeportal-uploads");
    }

    /** Largest accepted upload, in bytes. */
    public static long uploadMaxBytes() {
        return getInt("upload.max.bytes", 5 * 1024 * 1024);
    }

    public static String dbUser()   { return get("db.user", "badgeuser"); }
    public static String dbPass()   { return get("db.password", "badgepass123"); }
    public static int    poolSize() { return getInt("db.pool.size", 16); }

    /**
     * The verification-code signing secret.
     *
     * The real value lives in config/badgeportal.properties, which is
     * gitignored: anyone holding it can forge a code that passes the
     * tamper-evident check in VerifyServlet. The fallback below is a
     * deliberate placeholder, not the real key.
     *
     * It still has to MATCH the fallback in cgi/dbconfig.py, so that a
     * fresh checkout with no properties file has both implementations
     * agreeing with each other -- otherwise every badge issued by the
     * servlet would read as TAMPERED through CGI.
     */
    public static String secret()   {
        return get("badge.secret", "CHANGE_ME_TO_A_LONG_RANDOM_STRING");
    }
}
