package io.github.madaai.idlestow.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsed command line. Supported flags:
 * {@code --background}, {@code --read-only}, {@code --data-dir <path>} (or {@code --data-dir=<path>}),
 * {@code --smoke-test}, {@code --help}.
 */
public record LaunchArgs(boolean background, boolean readOnly, Path dataDir, boolean smokeTest, boolean help,
                         Path selfTestDir) {

    public static final String USAGE = """
            IdleStow - Idle files. Reclaimed space.

            Usage: idlestow [options]
              --background        start without a window (system tray only)
              --read-only         index and recommend only; never change any file
              --data-dir <path>   use <path> for the database, settings and logs
              --smoke-test        build every view against a temporary data directory, print SMOKE OK, exit
              --self-test <dir>   headless end-to-end check on synthetic files in an EMPTY folder, then exit
              --help              show this text
            """;

    /** Thrown for unknown flags or missing values. */
    public static final class ArgumentException extends Exception {
        private static final long serialVersionUID = 1L;

        public ArgumentException(String message) {
            super(message);
        }
    }

    public static LaunchArgs parse(String[] args) throws ArgumentException {
        boolean background = false;
        boolean readOnly = false;
        boolean smoke = false;
        boolean help = false;
        Path dataDir = null;
        Path selfTest = null;
        List<String> list = new ArrayList<>(List.of(args == null ? new String[0] : args));
        for (int i = 0; i < list.size(); i++) {
            String a = list.get(i);
            switch (a) {
                case "--background" -> background = true;
                case "--read-only" -> readOnly = true;
                case "--smoke-test" -> smoke = true;
                case "--help", "-h", "/?" -> help = true;
                case "--self-test" -> {
                    if (i + 1 >= list.size() || list.get(i + 1).startsWith("--")) {
                        throw new ArgumentException("--self-test requires an empty folder path");
                    }
                    selfTest = toPath(list.get(++i));
                }
                case "--data-dir" -> {
                    if (i + 1 >= list.size() || list.get(i + 1).startsWith("--")) {
                        throw new ArgumentException("--data-dir requires a folder path");
                    }
                    dataDir = toPath(list.get(++i));
                }
                default -> {
                    if (a.startsWith("--data-dir=")) {
                        dataDir = toPath(a.substring("--data-dir=".length()));
                    } else {
                        throw new ArgumentException("Unknown option: " + a);
                    }
                }
            }
        }
        return new LaunchArgs(background, readOnly, dataDir, smoke, help, selfTest);
    }

    private static Path toPath(String s) throws ArgumentException {
        if (s.isBlank()) throw new ArgumentException("--data-dir requires a folder path");
        try {
            return Path.of(s).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new ArgumentException("Invalid path for --data-dir: " + s);
        }
    }
}
