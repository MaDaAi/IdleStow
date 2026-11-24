package io.github.madaai.idlestow.core.platform;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Filesystem-native transparent compression (e.g. NTFS LZNT1).
 *
 * <p>Implementations must open the file once, verify through that same handle that the
 * file identity equals {@code expectedKey} (to defeat path swaps between validation and
 * mutation), and only then change the compression state of that single file. They must
 * never touch directories (no compression inheritance changes) or any other file.
 */
public interface NativeCompression {

    /** Whether native compression is implemented on this platform at all. */
    boolean implemented();

    /** Whether the given volume supports it (see {@link VolumeInfo#nativeCompression()}). */
    boolean supportedOn(VolumeInfo volume);

    /** Returns true if the file is currently natively compressed. */
    boolean isCompressed(Path file) throws IOException;

    /**
     * Compresses ({@code compress=true}) or decompresses one regular file.
     *
     * @throws FileIdentityMismatchException when the opened handle does not refer to {@code expectedKey}
     * @throws FileLockedException           when the file is in use and cannot be opened safely
     * @throws IOException                   on any OS failure (nothing is changed on failure)
     */
    void setCompressed(Path file, FileKey expectedKey, boolean compress) throws IOException;

    final class FileIdentityMismatchException extends IOException {
        public FileIdentityMismatchException(String message) { super(message); }
    }

    final class FileLockedException extends IOException {
        public FileLockedException(String message) { super(message); }
    }

    /** Implementation for platforms without native compression support. */
    NativeCompression UNSUPPORTED = new NativeCompression() {
        @Override public boolean implemented() { return false; }
        @Override public boolean supportedOn(VolumeInfo volume) { return false; }
        @Override public boolean isCompressed(Path file) { return false; }
        @Override public void setCompressed(Path file, FileKey expectedKey, boolean compress) throws IOException {
            throw new IOException("Native compression is not supported on this platform");
        }
    };
}
