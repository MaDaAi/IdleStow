package io.github.madaai.idlestow.platform.windows;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APITypeMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Minimal kernel32 binding for functions that jna-platform does not expose conveniently, plus helpers. */
final class Win32 {

    private Win32() { }

    interface Lib extends StdCallLibrary {
        HANDLE CreateFileW(WString name, int access, int share, Pointer security, int disposition, int flags, Pointer template);
        boolean CloseHandle(HANDLE h);
        boolean GetFileInformationByHandle(HANDLE h, Pointer info);
        boolean GetFileInformationByHandleEx(HANDLE h, int infoClass, Pointer buf, int size);
        boolean SetFileInformationByHandle(HANDLE h, int infoClass, Pointer buf, int size);
        int GetCompressedFileSizeW(WString name, IntByReference high);
        boolean GetVolumePathNameW(WString name, char[] buf, int len);
        boolean GetVolumeInformationW(WString root, char[] volName, int volLen, IntByReference serial,
                                      IntByReference maxComponent, IntByReference flags, char[] fsName, int fsLen);
        boolean GetDiskFreeSpaceW(WString root, IntByReference sectorsPerCluster, IntByReference bytesPerSector,
                                  IntByReference freeClusters, IntByReference totalClusters);
        int GetDriveTypeW(WString root);
        boolean DeviceIoControl(HANDLE h, int code, Pointer in, int inSize, Pointer out, int outSize,
                                IntByReference returned, Pointer overlapped);
        HANDLE FindFirstStreamW(WString name, int infoLevel, Pointer data, int flags);
        boolean FindNextStreamW(HANDLE h, Pointer data);
        boolean FindClose(HANDLE h);
        boolean GetSystemPowerStatus(Pointer status);
        boolean WriteFile(HANDLE h, byte[] buf, int len, IntByReference written, Pointer overlapped);
    }

    static final Lib K32 = Native.load("kernel32", Lib.class, Map.of(
            Library.OPTION_TYPE_MAPPER, W32APITypeMapper.UNICODE));

    /** Forces class initialization (and therefore the native library load). */
    static void ensureLoaded() { }

    // access / share / disposition / flags
    static final int FILE_READ_ATTRIBUTES = 0x80;
    static final int GENERIC_READ = 0x80000000;
    static final int GENERIC_WRITE = 0x40000000;
    static final int FILE_SHARE_READ = 1, FILE_SHARE_WRITE = 2, FILE_SHARE_DELETE = 4;
    static final int CREATE_ALWAYS = 2, OPEN_EXISTING = 3, OPEN_ALWAYS = 4;
    static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    static final int FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;

    // attributes
    static final int ATTR_DIRECTORY = 0x10, ATTR_SYSTEM = 0x4, ATTR_SPARSE = 0x200, ATTR_REPARSE = 0x400,
            ATTR_COMPRESSED = 0x800, ATTR_OFFLINE = 0x1000, ATTR_ENCRYPTED = 0x4000,
            ATTR_RECALL_ON_OPEN = 0x40000, ATTR_RECALL_ON_DATA_ACCESS = 0x400000;

    static final int FSCTL_GET_COMPRESSION = 0x9003C;
    static final int FSCTL_SET_COMPRESSION = 0x9C040;

    static final int ERROR_SHARING_VIOLATION = 32, ERROR_LOCK_VIOLATION = 33, ERROR_HANDLE_EOF = 38;

    /** IOException carrying the Win32 error code. */
    static final class Win32Exception extends IOException {
        private static final long serialVersionUID = 1L;
        private final int code;
        Win32Exception(String what, int code) {
            super(what + " failed: Win32 error " + code + " (" + describe(code) + ")");
            this.code = code;
        }
        int code() { return code; }
        boolean isSharingProblem() { return code == ERROR_SHARING_VIOLATION || code == ERROR_LOCK_VIOLATION; }
    }

    static String describe(int code) {
        try {
            return Kernel32Util.formatMessage(code).trim();
        } catch (RuntimeException | LinkageError e) {
            return "unknown";
        }
    }

    /** Win32 name for a path: absolute, normalized, with the {@code \\?\} prefix when longer than 240 chars. */
    static String longName(Path path) {
        return longName(path.toAbsolutePath().normalize().toString());
    }

    static String longName(String abs) {
        if (abs.startsWith("\\\\?\\") || abs.length() <= 240) return abs;
        if (abs.startsWith("\\\\")) return "\\\\?\\UNC\\" + abs.substring(2);
        return "\\\\?\\" + abs;
    }

    static boolean valid(HANDLE h) {
        return h != null && !WinBase.INVALID_HANDLE_VALUE.equals(h);
    }

    static HANDLE open(String name, int access, int share, int disposition, int flags) throws Win32Exception {
        HANDLE h = K32.CreateFileW(new WString(name), access, share, null, disposition, flags, null);
        if (!valid(h)) {
            int err = Native.getLastError();
            throw new Win32Exception("CreateFileW(" + name + ")", err);
        }
        return h;
    }

    static void close(HANDLE h) {
        if (valid(h)) K32.CloseHandle(h);
    }

    /** Snapshot of BY_HANDLE_FILE_INFORMATION. */
    record HandleInfo(int attributes, long creation, long access, long write, int volumeSerial,
                      long size, int links, long fileIndex) {
        String volumeIdHex() { return String.format("%08X", volumeSerial); }
        String fileIdHex() { return String.format("%016X", fileIndex); }
    }

    static HandleInfo handleInfo(HANDLE h) throws Win32Exception {
        Memory m = new Memory(52);
        m.clear();
        if (!K32.GetFileInformationByHandle(h, m)) {
            throw new Win32Exception("GetFileInformationByHandle", Native.getLastError());
        }
        long size = (m.getInt(32) & 0xFFFFFFFFL) << 32 | (m.getInt(36) & 0xFFFFFFFFL);
        long index = (m.getInt(44) & 0xFFFFFFFFL) << 32 | (m.getInt(48) & 0xFFFFFFFFL);
        return new HandleInfo(m.getInt(0), m.getLong(4), m.getLong(12), m.getLong(20), m.getInt(28),
                size, m.getInt(40), index);
    }

    /** FILE_BASIC_INFO (FileBasicInfo = 0): creation, lastAccess, lastWrite, change (FILETIME ticks) + attributes. */
    record BasicInfo(long creation, long lastAccess, long lastWrite, long change, int attributes) { }

    static BasicInfo basicInfo(HANDLE h) throws Win32Exception {
        Memory m = new Memory(40);
        m.clear();
        if (!K32.GetFileInformationByHandleEx(h, 0, m, 40)) {
            throw new Win32Exception("GetFileInformationByHandleEx(FileBasicInfo)", Native.getLastError());
        }
        return new BasicInfo(m.getLong(0), m.getLong(8), m.getLong(16), m.getLong(24), m.getInt(32));
    }

    /** Sets only lastAccess and lastWrite (zero fields are left unchanged by the OS). */
    static void setAccessAndWrite(HANDLE h, long lastAccess, long lastWrite) throws Win32Exception {
        Memory m = new Memory(40);
        m.clear();
        m.setLong(8, lastAccess);
        m.setLong(16, lastWrite);
        if (!K32.SetFileInformationByHandle(h, 0, m, 40)) {
            throw new Win32Exception("SetFileInformationByHandle(FileBasicInfo)", Native.getLastError());
        }
    }

    static int getCompressionFormat(HANDLE h) throws Win32Exception {
        Memory out = new Memory(2);
        out.clear();
        IntByReference ret = new IntByReference();
        if (!K32.DeviceIoControl(h, FSCTL_GET_COMPRESSION, null, 0, out, 2, ret, null)) {
            throw new Win32Exception("FSCTL_GET_COMPRESSION", Native.getLastError());
        }
        return out.getShort(0) & 0xFFFF;
    }
}
