package com.badgeportal;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * One timestamp format, used everywhere: "yyyy-MM-dd HH:mm:ss" in UTC.
 *
 * Deliberately plain. The CGI script has to produce byte-identical
 * output from the same epoch-millisecond value, and Python and Java
 * disagree about the details of ISO-8601 (fractional seconds, the "Z"
 * suffix, the "T" separator). Pinning both to this one pattern means
 * bench/benchmark.py can assert that the two responses are literally
 * the same JSON, which is what proves the comparison is fair.
 *
 * SimpleDateFormat is not thread-safe, so a new one is created per call
 * rather than shared in a static field. Verification is dominated by
 * the database round-trip; this allocation does not register.
 */
public final class TimeFmt {

    private TimeFmt() { }

    public static String utc(long epochMillis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(epochMillis));
    }
}
