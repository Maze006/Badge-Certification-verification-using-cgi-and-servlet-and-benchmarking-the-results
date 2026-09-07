package com.badgeportal;

/**
 * A deliberately tiny JSON writer.
 *
 * The CGI script gets json.dumps from the Python standard library for
 * free; pulling in Jackson or Gson on the Java side purely to match it
 * would add a jar to the comparison for no pedagogical gain. Both
 * implementations must emit the SAME response shape -- that is the
 * property the benchmark depends on -- and for a handful of flat
 * objects this is enough.
 *
 * Output is compact, with no spaces around the separators, matching
 * Python's json.dumps(separators=(",", ":")) exactly.
 */
public final class Json {

    private final StringBuilder sb = new StringBuilder(256);
    private boolean first = true;

    public Json() {
        sb.append('{');
    }

    private void comma() {
        if (!first) sb.append(',');
        first = false;
    }

    public Json put(String key, String value) {
        comma();
        quote(key).append(':');
        if (value == null) sb.append("null"); else quote(value);
        return this;
    }

    public Json put(String key, long value) {
        comma();
        quote(key).append(':').append(value);
        return this;
    }

    public Json put(String key, double value) {
        comma();
        quote(key).append(':').append(value);
        return this;
    }

    public Json put(String key, boolean value) {
        comma();
        quote(key).append(':').append(value);
        return this;
    }

    /** Inserts an already-serialised fragment (a nested object or array). */
    public Json putRaw(String key, String rawJson) {
        comma();
        quote(key).append(':').append(rawJson);
        return this;
    }

    public String end() {
        return sb.append('}').toString();
    }

    private StringBuilder quote(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append('\\').append('"');
                    break;
                case '\\':
                    sb.append('\\').append('\\');
                    break;
                case '\n':
                    sb.append('\\').append('n');
                    break;
                case '\r':
                    sb.append('\\').append('r');
                    break;
                case '\t':
                    sb.append('\\').append('t');
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"');
    }

    /** Escapes text for safe interpolation into an HTML page. */
    public static String html(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':  out.append("&amp;");  break;
                case '<':  out.append("&lt;");   break;
                case '>':  out.append("&gt;");   break;
                case '"':  out.append("&quot;"); break;
                case '\'': out.append("&#39;");  break;
                default:   out.append(c);
            }
        }
        return out.toString();
    }
}
