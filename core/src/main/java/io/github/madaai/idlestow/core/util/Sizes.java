package io.github.madaai.idlestow.core.util;

import java.util.Locale;

/** Binary (IEC) size formatting. IdleStow always uses KiB/MiB/GiB to avoid GB/GiB ambiguity. */
public final class Sizes {
    public static final long KIB = 1024L;
    public static final long MIB = 1024L * KIB;
    public static final long GIB = 1024L * MIB;

    private Sizes() { }

    public static String format(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < KIB) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : (v >= 10 ? "%.1f %s" : "%.2f %s"), v, units[u]);
    }

    /** Signed variant, e.g. "+3.2 MiB" / "−1.0 KiB". */
    public static String formatSigned(long bytes) {
        return (bytes < 0 ? "−" : "") + format(Math.abs(bytes));
    }

    /** Rounds {@code size} up to a multiple of {@code cluster} (cluster ≤ 0 → unchanged). */
    public static long roundUp(long size, long cluster) {
        if (cluster <= 0 || size <= 0) return Math.max(0, size);
        return ((size + cluster - 1) / cluster) * cluster;
    }
}
