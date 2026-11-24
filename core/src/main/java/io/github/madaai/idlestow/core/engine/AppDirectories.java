package io.github.madaai.idlestow.core.engine;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Platform-specific per-user data locations. Runtime data never lives in the installation or source tree.
 * Override with system property {@code idlestow.home} or environment variable {@code IDLESTOW_HOME}.
 */
public final class AppDirectories {
    private AppDirectories() { }

    public static Path dataDir() {
        String override = System.getProperty("idlestow.home");
        if (override == null || override.isBlank()) override = System.getenv("IDLESTOW_HOME");
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath().normalize();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.startsWith("windows")) {
            String local = System.getenv("LOCALAPPDATA");
            return Path.of(local != null && !local.isBlank() ? local : home + "\\AppData\\Local", "IdleStow");
        }
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "IdleStow");
        String xdg = System.getenv("XDG_DATA_HOME");
        return Path.of(xdg != null && !xdg.isBlank() ? xdg : home + "/.local/share", "idlestow");
    }

    public static Path logDir(Path dataDir) {
        return dataDir.resolve("logs");
    }

    public static Path databaseFile(Path dataDir) {
        return dataDir.resolve("idlestow.db");
    }
}
