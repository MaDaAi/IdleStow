package io.github.madaai.idlestow.core.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/** Timestamp helpers. All persisted timestamps are UTC (ISO-8601 strings or epoch millis). */
public final class Times {
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault());

    private Times() { }

    public static String iso(Instant i) { return i == null ? null : i.toString(); }

    public static String nowIso(Clock clock) { return clock.instant().toString(); }

    public static Instant parse(String s) { return s == null || s.isBlank() ? null : Instant.parse(s); }

    /** Locale-aware presentation in the user's time zone. */
    public static String display(Instant i) { return i == null ? "—" : LOCAL.format(i); }

    public static String displayMillis(long ms) { return ms <= 0 ? "—" : display(Instant.ofEpochMilli(ms)); }

    /** Human friendly relative age like "3 days ago". */
    public static String ago(long epochMs, Clock clock) {
        if (epochMs <= 0) return "unknown";
        long d = Duration.between(Instant.ofEpochMilli(epochMs), clock.instant()).toMinutes();
        if (d < 0) return "in the future";
        if (d < 2) return "just now";
        if (d < 60) return d + " minutes ago";
        long h = d / 60;
        if (h < 48) return h + " hours ago";
        long days = h / 24;
        return days + " days ago";
    }

    public static long daysBetween(long fromMs, long toMs) {
        return Math.floorDiv(toMs - fromMs, 86_400_000L);
    }
}
