package io.github.madaai.idlestow.platform.windows;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import io.github.madaai.idlestow.core.platform.FileKey;
import io.github.madaai.idlestow.core.platform.NativeCompression;
import io.github.madaai.idlestow.core.platform.VolumeInfo;

import java.io.IOException;
import java.nio.file.Path;

import static io.github.madaai.idlestow.platform.windows.Win32.*;

/**
 * NTFS (LZNT1) per-file compression through {@code FSCTL_SET_COMPRESSION}. Never calls compact.exe and never
 * touches directories.
 *
 * <p>Timestamp behaviour: LastWriteTime/LastAccessTime are captured from the same handle before the ioctl and, if
 * they differ afterwards, restored with {@code SetFileInformationByHandle}. See {@code WindowsNativeTest} for the
 * measurement of whether the ioctl changes them on its own.
 */
public final class WindowsCompression implements NativeCompression {

    private static final short COMPRESSION_FORMAT_NONE = 0;
    private static final short COMPRESSION_FORMAT_DEFAULT = 1;

    private final boolean restoreTimestamps;

    WindowsCompression() { this(true); }

    /** @param restoreTimestamps test hook: {@code false} exposes the raw timestamp behaviour of the ioctl. */
    WindowsCompression(boolean restoreTimestamps) {
        Win32.ensureLoaded();
        this.restoreTimestamps = restoreTimestamps;
    }

    @Override public boolean implemented() { return true; }

    @Override
    public boolean supportedOn(VolumeInfo volume) {
        return volume != null && volume.nativeCompression();
    }

    @Override
    public boolean isCompressed(Path file) throws IOException {
        HANDLE h = open(longName(file), FILE_READ_ATTRIBUTES,
                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, OPEN_EXISTING,
                FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS);
        try {
            return (handleInfo(h).attributes() & ATTR_COMPRESSED) != 0;
        } finally {
            close(h);
        }
    }

    @Override
    public void setCompressed(Path file, FileKey expectedKey, boolean compress) throws IOException {
        HANDLE h;
        try {
            h = open(longName(file), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ, OPEN_EXISTING,
                    FILE_FLAG_OPEN_REPARSE_POINT);
        } catch (Win32Exception e) {
            if (e.isSharingProblem()) {
                throw new FileLockedException("File is in use by another process: " + file + " (" + e.getMessage() + ")");
            }
            throw e;
        }
        try {
            HandleInfo info = handleInfo(h);
            FileKey actual = new FileKey(info.volumeIdHex(), info.fileIdHex());
            if (!actual.equals(expectedKey)) {
                throw new FileIdentityMismatchException("Opened file " + actual.asString()
                        + " does not match expected " + expectedKey.asString() + ": " + file);
            }
            if ((info.attributes() & ATTR_DIRECTORY) != 0) {
                throw new IOException("Refusing to change compression of a directory: " + file);
            }
            if ((info.attributes() & ATTR_REPARSE) != 0) {
                throw new IOException("Refusing to change compression of a reparse point: " + file);
            }

            BasicInfo before = basicInfo(h);
            Memory in = new Memory(2);
            in.setShort(0, compress ? COMPRESSION_FORMAT_DEFAULT : COMPRESSION_FORMAT_NONE);
            IntByReference returned = new IntByReference();
            if (!K32.DeviceIoControl(h, FSCTL_SET_COMPRESSION, in, 2, null, 0, returned, null)) {
                int err = Native.getLastError();
                throw new Win32Exception("FSCTL_SET_COMPRESSION(" + file + ")", err);
            }

            int fmt = getCompressionFormat(h);
            if ((fmt != 0) != compress) {
                throw new IOException("Compression state of " + file + " did not change as requested (format " + fmt + ")");
            }
            if (restoreTimestamps) {
                BasicInfo after = basicInfo(h);
                if (after.lastWrite() != before.lastWrite() || after.lastAccess() != before.lastAccess()) {
                    setAccessAndWrite(h, before.lastAccess(), before.lastWrite());
                }
            }
        } finally {
            close(h);
        }
    }
}
