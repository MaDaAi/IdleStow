package io.github.madaai.idlestow.core.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Creates directory links for tests: a symbolic link when allowed, otherwise a Windows junction. */
public final class LinkSupport {
    private LinkSupport() { }

    /** Creates {@code link} pointing at directory {@code target}; returns false when neither mechanism works. */
    public static boolean createDirLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // fall through to a junction on Windows
        }
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) return false;
        try {
            Process p = new ProcessBuilder(List.of("cmd", "/c", "mklink", "/J", link.toString(), target.toString()))
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && Files.exists(link);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Creates a symbolic link to a file; returns false when the OS refuses (e.g. missing privilege). */
    public static boolean createFileSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }
}
