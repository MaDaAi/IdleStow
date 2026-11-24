package io.github.madaai.idlestow.core.util;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/** Path normalization and containment checks that are safe on case-insensitive filesystems. */
public final class PathUtil {
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private PathUtil() { }

    public static boolean isWindows() { return WINDOWS; }

    /** Absolute + normalized (no symlink resolution). */
    public static Path normalize(Path p) {
        return p.toAbsolutePath().normalize();
    }

    /** Key used for comparisons and DB uniqueness: case-folded on Windows. */
    public static String key(Path p) {
        String s = normalize(p).toString();
        return WINDOWS ? s.toLowerCase(Locale.ROOT) : s;
    }

    /** True when {@code child} equals {@code parent} or lies inside it (lexically, after normalization). */
    public static boolean isWithin(Path child, Path parent) {
        Path c = normalize(child);
        Path p = normalize(parent);
        if (WINDOWS) {
            String cs = c.toString().toLowerCase(Locale.ROOT);
            String ps = p.toString().toLowerCase(Locale.ROOT);
            if (cs.equals(ps)) return true;
            String prefix = ps.endsWith("\\") ? ps : ps + "\\";
            return cs.startsWith(prefix);
        }
        return c.startsWith(p);
    }

    /**
     * Verifies that {@code path} contains no symbolic links / junctions in any component by
     * comparing its real path with its lexical path. Returns false when any component is a link
     * (or when the path cannot be resolved).
     */
    public static boolean hasNoLinkComponents(Path path) {
        try {
            Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realParent = path.getParent() == null ? null : path.getParent().toRealPath();
            Path lexical = normalize(path);
            if (realParent != null && !sameText(realParent, lexical.getParent())) return false;
            return sameText(real, lexical);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean sameText(Path a, Path b) {
        if (a == null || b == null) return a == b;
        return WINDOWS ? a.toString().equalsIgnoreCase(b.toString()) : a.toString().equals(b.toString());
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
