package io.github.madaai.idlestow.core.jobs;

import io.github.madaai.idlestow.core.util.PathUtil;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Same-volume renames that never replace an existing target.
 *
 * <p>{@code Files.move(…, ATOMIC_MOVE)} replaces an existing target on Windows ({@code MoveFileEx} with
 * {@code MOVEFILE_REPLACE_EXISTING}) and on POSIX ({@code rename(2)}), so it must never be used for user
 * data. Instead:
 * <ul>
 *   <li>Windows: {@code Files.move} without options maps to {@code MoveFileEx} without the replace flag,
 *       which fails atomically if the target exists.</li>
 *   <li>POSIX: {@code link(2)} (fails atomically if the target exists) followed by {@code unlink(2)} of the
 *       source.</li>
 * </ul>
 * Cross-volume moves are refused: callers copy to a temporary sibling on the destination volume, verify
 * it, and then rename it with this class.
 */
public final class SafeMove {
    private SafeMove() { }

    /** Thrown when source and target are on different volumes. */
    public static final class CrossVolumeException extends IOException {
        public CrossVolumeException(String m) { super(m); }
    }

    public static void renameNoReplace(Path from, Path to) throws IOException {
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(to.toString());
        if (!sameStore(from, to)) throw new CrossVolumeException("Source and destination are on different volumes");
        if (PathUtil.isWindows()) {
            // No REPLACE_EXISTING, no ATOMIC_MOVE → MoveFileExW(from, to, 0): fails if `to` exists.
            Files.move(from, to);
            return;
        }
        Files.createLink(to, from); // atomic, fails with FileAlreadyExistsException if `to` exists
        try {
            Files.delete(from);
        } catch (IOException e) {
            // keep both names rather than lose anything; the caller sees the failure
            throw new IOException("Moved by hard link but could not remove the source name: " + e.getMessage(), e);
        }
    }

    static boolean sameStore(Path from, Path to) throws IOException {
        Path toDir = to.toAbsolutePath().getParent();
        FileStore a = Files.getFileStore(from);
        FileStore b = Files.getFileStore(toDir);
        return a.equals(b);
    }
}
