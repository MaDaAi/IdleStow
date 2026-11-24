package io.github.madaai.idlestow.app.util;

import io.github.madaai.idlestow.core.util.Sizes;
import io.github.madaai.idlestow.core.util.Times;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;

/** Pure presentation helpers (sizes in IEC units, durations, dates, labels). */
public final class Fmt {
    public static final String DASH = "—";
    public static final String APPROX = "≈ ";

    private Fmt() { }

    /** Measured size, IEC units; negative or unknown shows a dash. */
    public static String size(long bytes) {
        return bytes < 0 ? DASH : Sizes.format(bytes);
    }

    public static String size(Long bytes) {
        return bytes == null ? DASH : size(bytes.longValue());
    }

    /** An estimate is always prefixed so it cannot be mistaken for a measurement. */
    public static String estimate(Long bytes) {
        return bytes == null || bytes <= 0 ? DASH : APPROX + Sizes.format(bytes);
    }

    public static String estimate(long bytes) {
        return bytes <= 0 ? DASH : APPROX + Sizes.format(bytes);
    }

    public static String signedSize(long bytes) {
        return Sizes.formatSigned(bytes);
    }

    /** "1 h 05 min", "3 min 20 s", "42 s". */
    public static String duration(long millis) {
        if (millis < 0) return DASH;
        long s = millis / 1000;
        if (s < 60) return s + " s";
        long m = s / 60;
        if (m < 60) return m + " min " + (s % 60) + " s";
        long h = m / 60;
        if (h < 48) return h + " h " + String.format(Locale.ROOT, "%02d", m % 60) + " min";
        return (h / 24) + " d " + (h % 24) + " h";
    }

    public static String throughput(long bytes, long nanos) {
        if (bytes <= 0 || nanos <= 0) return DASH;
        double perSec = bytes / (nanos / 1_000_000_000.0);
        return Sizes.format((long) perSec) + "/s";
    }

    /** ETA is only offered when it can be computed from real progress; otherwise empty. */
    public static Optional<String> eta(long remainingBytes, double bytesPerSecond) {
        if (remainingBytes <= 0 || bytesPerSecond <= 1 || Double.isNaN(bytesPerSecond)) return Optional.empty();
        return Optional.of(duration((long) (remainingBytes / bytesPerSecond * 1000)));
    }

    public static String percent(long part, long total) {
        if (total <= 0) return DASH;
        return String.format(Locale.ROOT, "%.0f%%", 100.0 * part / total);
    }

    public static String iso(String isoInstant) {
        if (isoInstant == null || isoInstant.isBlank()) return DASH;
        try {
            return Times.display(Instant.parse(isoInstant));
        } catch (RuntimeException e) {
            return isoInstant;
        }
    }

    public static String epochMs(long ms) {
        return Times.displayMillis(ms);
    }

    /** Elapsed milliseconds between two ISO instants (end may be null = now), or -1 when unparsable. */
    public static long elapsedMs(String startIso, String endIso, long nowMs) {
        if (startIso == null) return -1;
        try {
            long s = Instant.parse(startIso).toEpochMilli();
            long e = endIso == null ? nowMs : Instant.parse(endIso).toEpochMilli();
            return Math.max(0, e - s);
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    /** Idle days from the classifier, -1 = unknown. */
    public static String idle(long idleDays) {
        if (idleDays < 0) return DASH;
        if (idleDays == 0) return "today";
        return idleDays == 1 ? "1 day idle" : idleDays + " days idle";
    }

    public static String title(String enumName) {
        if (enumName == null || enumName.isBlank()) return DASH;
        String s = enumName.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String category(String c) {
        return title(c);
    }

    /** Converts SQLite's integer julian day (as produced by the history query) to a date. */
    public static LocalDate fromJulianDay(long julianDay) {
        return LocalDate.ofEpochDay(julianDay - 2_440_587L);
    }

    public static String fileName(String path) {
        if (path == null) return "";
        int i = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        return i < 0 || i == path.length() - 1 ? path : path.substring(i + 1);
    }

    /** Shortens long paths in the middle, keeping the start and the file name. */
    public static String middleEllipsis(String s, int max) {
        if (s == null || s.length() <= max || max < 8) return s == null ? "" : s;
        int keep = max - 1;
        int tail = keep * 2 / 3;
        int head = keep - tail;
        return s.substring(0, head) + "…" + s.substring(s.length() - tail);
    }

    public static String plural(long n, String singular) {
        return n + " " + singular + (n == 1 ? "" : "s");
    }
}
