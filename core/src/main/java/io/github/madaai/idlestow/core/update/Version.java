package io.github.madaai.idlestow.core.update;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A plain release version {@code MAJOR.MINOR.PATCH}, optionally written with a leading "v" (tags). */
public record Version(int major, int minor, int patch) implements Comparable<Version> {
    private static final Pattern P = Pattern.compile("v?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})");

    /** Parses "1.2.3" or "v1.2.3". Anything else (including pre-release suffixes) is rejected. */
    public static Optional<Version> parse(String s) {
        if (s == null) return Optional.empty();
        Matcher m = P.matcher(s.trim());
        if (!m.matches()) return Optional.empty();
        return Optional.of(new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))));
    }

    @Override
    public int compareTo(Version o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) c = Integer.compare(minor, o.minor);
        if (c == 0) c = Integer.compare(patch, o.patch);
        return c;
    }

    public boolean newerThan(Version o) {
        return compareTo(o) > 0;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
