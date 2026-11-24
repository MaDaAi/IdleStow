package io.github.madaai.idlestow.platform.windows;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.IntByReference;
import io.github.madaai.idlestow.core.platform.FileInspector;
import io.github.madaai.idlestow.core.platform.FileKey;
import io.github.madaai.idlestow.core.platform.NativeFileState;
import io.github.madaai.idlestow.core.platform.VolumeInfo;
import io.github.madaai.idlestow.core.platform.VolumeInfo.AccessTracking;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.madaai.idlestow.platform.windows.Win32.*;

/**
 * Read-only NTFS inspection through the Win32 API.
 *
 * <p>Allocation semantics: {@code GetCompressedFileSizeW} returns the on-disk allocation for compressed and sparse
 * files but simply the logical size for every other file. For uncompressed, non-sparse files the logical size is
 * therefore rounded up to the volume cluster size so that the value is comparable with compressed allocations.
 * Very small files stored resident in the MFT actually use no clusters; they are still reported as one cluster
 * (a conservative over-estimate). Cloud placeholders, reparse points and directories are never queried by path
 * (that could follow or hydrate them): their allocation is reported as unknown (-1) or not exact.
 */
public final class WindowsInspector implements FileInspector {

    private static final String FS_KEY = "SYSTEM\\CurrentControlSet\\Control\\FileSystem";
    private static final String FS_VALUE = "NtfsDisableLastAccessUpdate";
    private static final int FILE_FILE_COMPRESSION = 0x10;
    private static final int FILE_READ_ONLY_VOLUME = 0x80000;
    private static final int DRIVE_REMOTE = 4;

    /** volume root -> cluster size (-1 when unknown). */
    private final Map<String, Long> clusterCache = new ConcurrentHashMap<>();

    WindowsInspector() {
        Win32.ensureLoaded();
    }

    // ------------------------------------------------------------------ volume

    @Override
    public VolumeInfo volumeOf(Path path) throws IOException {
        String root = volumeRoot(path);
        WString wroot = new WString(root);

        char[] fsName = new char[64];
        IntByReference serial = new IntByReference(), maxComp = new IntByReference(), flags = new IntByReference();
        if (!K32.GetVolumeInformationW(wroot, null, 0, serial, maxComp, flags, fsName, fsName.length)) {
            throw new Win32Exception("GetVolumeInformationW(" + root + ")", Native.getLastError());
        }
        String fs = Native.toString(fsName);
        String volumeId = String.format("%08X", serial.getValue());
        boolean readOnly = (flags.getValue() & FILE_READ_ONLY_VOLUME) != 0;
        boolean compressionFlag = (flags.getValue() & FILE_FILE_COMPRESSION) != 0;
        boolean remote = K32.GetDriveTypeW(wroot) == DRIVE_REMOTE;
        long cluster = clusterSizeOfRoot(root);

        boolean ntfs = "NTFS".equalsIgnoreCase(fs);
        String note;
        boolean supported = false;
        if (!ntfs) note = "Native compression requires NTFS; this volume uses " + fs + ".";
        else if (remote) note = "Native compression is not used on network volumes.";
        else if (readOnly) note = "The volume is read only.";
        else if (!compressionFlag) note = "The volume does not support file compression.";
        else if (cluster <= 0) note = "The cluster size of the volume could not be determined.";
        else if (cluster > 4096) note = "NTFS compression is unavailable for cluster sizes above 4 KiB (this volume uses "
                + cluster + " bytes).";
        else { supported = true; note = ""; }

        AccessTracking tracking = AccessTracking.UNKNOWN;
        String trackingNote = "Last-access tracking reliability cannot be determined for " + fs + " volumes.";
        if (ntfs && !remote) {
            tracking = readAccessTracking();
            trackingNote = trackingNoteFor(tracking);
        }
        return new VolumeInfo(volumeId, root, fs, supported, note, cluster, readOnly, remote, tracking, trackingNote);
    }

    static String trackingNoteFor(AccessTracking t) {
        return switch (t) {
            case ENABLED -> "NTFS updates last-access times (≈1 hour granularity)";
            case DISABLED -> "NTFS last-access updates are disabled on this system; access times are not reliable.";
            default -> "The NTFS last-access update setting could not be read.";
        };
    }

    /** Pure mapping of the registry value {@code NtfsDisableLastAccessUpdate}. */
    static AccessTracking accessTrackingFromRegistry(int value) {
        return (value & 1) == 1 ? AccessTracking.DISABLED : AccessTracking.ENABLED;
    }

    private static AccessTracking readAccessTracking() {
        try {
            if (!Advapi32Util.registryValueExists(WinReg.HKEY_LOCAL_MACHINE, FS_KEY, FS_VALUE)) {
                return AccessTracking.UNKNOWN;
            }
            return accessTrackingFromRegistry(
                    Advapi32Util.registryGetIntValue(WinReg.HKEY_LOCAL_MACHINE, FS_KEY, FS_VALUE));
        } catch (RuntimeException e) {
            return AccessTracking.UNKNOWN;
        }
    }

    private static String volumeRoot(Path path) throws IOException {
        String abs = longName(path.toAbsolutePath().normalize().toString());
        char[] buf = new char[1024];
        if (!K32.GetVolumePathNameW(new WString(abs), buf, buf.length)) {
            throw new Win32Exception("GetVolumePathNameW(" + abs + ")", Native.getLastError());
        }
        return Native.toString(buf);
    }

    private long clusterSizeOfRoot(String root) {
        return clusterCache.computeIfAbsent(root, r -> {
            IntByReference spc = new IntByReference(), bps = new IntByReference(),
                    free = new IntByReference(), total = new IntByReference();
            if (!K32.GetDiskFreeSpaceW(new WString(r), spc, bps, free, total)) return -1L;
            return (long) spc.getValue() * (long) bps.getValue();
        });
    }

    // ------------------------------------------------------------------ file

    @Override
    public NativeFileState inspect(Path path) throws IOException {
        String name = longName(path);
        HANDLE h = open(name, FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS);
        HandleInfo info;
        try {
            info = handleInfo(h);
        } finally {
            close(h);
        }
        int a = info.attributes();
        boolean compressed = (a & ATTR_COMPRESSED) != 0;
        boolean sparse = (a & ATTR_SPARSE) != 0;
        boolean reparse = (a & ATTR_REPARSE) != 0;
        boolean dir = (a & ATTR_DIRECTORY) != 0;
        boolean cloud = (a & (ATTR_RECALL_ON_OPEN | ATTR_RECALL_ON_DATA_ACCESS)) != 0;

        long allocated = -1;
        boolean exact = false;
        if (!dir && !cloud && !reparse) {
            long physical = compressedFileSize(name);
            if (physical >= 0) {
                if (compressed || sparse) {
                    allocated = physical;
                    exact = true;
                } else {
                    long cluster = clusterSizeForFile(path);
                    if (cluster > 0) {
                        allocated = roundUp(physical, cluster);
                        exact = true;
                    } else {
                        allocated = physical;
                    }
                }
            } else {
                allocated = info.size();
            }
        } else if (reparse && !cloud && !dir) {
            allocated = info.size();
        }

        int streams = (dir || reparse) ? -1 : countAlternateStreams(name);
        return new NativeFileState(new FileKey(info.volumeIdHex(), info.fileIdHex()),
                allocated, exact, compressed, (a & ATTR_ENCRYPTED) != 0, sparse, reparse, cloud,
                (a & ATTR_OFFLINE) != 0, (a & ATTR_SYSTEM) != 0, info.links(), streams);
    }

    static long roundUp(long value, long unit) {
        return (value + unit - 1) / unit * unit;
    }

    private long clusterSizeForFile(Path path) {
        try {
            return clusterSizeOfRoot(volumeRoot(path));
        } catch (IOException e) {
            return -1;
        }
    }

    /** Returns -1 on failure. */
    private static long compressedFileSize(String name) {
        IntByReference high = new IntByReference();
        int low = K32.GetCompressedFileSizeW(new WString(name), high);
        if (low == -1 && Native.getLastError() != 0) return -1;
        return (high.getValue() & 0xFFFFFFFFL) << 32 | (low & 0xFFFFFFFFL);
    }

    /** Counts named streams (everything except the unnamed {@code ::$DATA}); -1 on failure. */
    static int countAlternateStreams(String name) {
        Memory data = new Memory(8 + 296 * 2); // WIN32_FIND_STREAM_DATA
        data.clear();
        HANDLE h = K32.FindFirstStreamW(new WString(name), 0, data, 0);
        if (!valid(h)) {
            return Native.getLastError() == ERROR_HANDLE_EOF ? 0 : -1;
        }
        try {
            int count = 0;
            do {
                if (!"::$DATA".equals(data.getWideString(8))) count++;
            } while (K32.FindNextStreamW(h, data));
            return count;
        } finally {
            K32.FindClose(h);
        }
    }

    @Override
    public Boolean isLockedByOtherProcess(Path path) {
        try {
            HANDLE h = open(longName(path), GENERIC_READ, FILE_SHARE_READ, OPEN_EXISTING,
                    FILE_FLAG_OPEN_REPARSE_POINT);
            close(h);
            return Boolean.FALSE;
        } catch (Win32Exception e) {
            return e.isSharingProblem() ? Boolean.TRUE : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
