package io.github.madaai.idlestow.platform.windows;

import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import io.github.madaai.idlestow.core.platform.FileKey;
import io.github.madaai.idlestow.core.platform.NativeCompression.FileIdentityMismatchException;
import io.github.madaai.idlestow.core.platform.NativeCompression.FileLockedException;
import io.github.madaai.idlestow.core.platform.NativeFileState;
import io.github.madaai.idlestow.core.platform.VolumeInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static io.github.madaai.idlestow.platform.windows.Win32.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Win32 calls. Only ever touches files created inside a JUnit temp directory. */
@Tag("native")
@EnabledOnOs(OS.WINDOWS)
class WindowsNativeTest {

    static WindowsPlatform platform;

    @BeforeAll
    static void init() {
        platform = new WindowsPlatform("IdleStowTest-" + UUID.randomUUID());
    }

    private static byte[] compressibleBytes(int n) {
        byte[] pattern = "IdleStow compressible content 0123456789 ".getBytes(StandardCharsets.US_ASCII);
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = pattern[i % pattern.length];
        return b;
    }

    private static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    private FileKey keyOf(Path p) throws IOException {
        return platform.fileInspector().inspect(p).fileKey();
    }

    private void assumeCompressionSupported(Path dir) throws IOException {
        VolumeInfo v = platform.fileInspector().volumeOf(dir);
        assumeTrue(v.nativeCompression(), "temp volume does not support NTFS compression: " + v.nativeCompressionNote());
    }

    @Test
    void volumeOfTempDir(@TempDir Path dir) throws IOException {
        VolumeInfo v = platform.fileInspector().volumeOf(dir);
        System.out.println("[native] " + v);
        assertEquals("NTFS", v.fileSystemType());
        assertTrue(v.volumeId().matches("[0-9A-F]{8}"), v.volumeId());
        assertTrue(v.clusterSize() > 0);
        assertFalse(v.remote());
        assertNotEquals(VolumeInfo.AccessTracking.COARSE, v.accessTracking());
        assertFalse(v.accessTrackingNote().isEmpty());
    }

    @Test
    void fileKeyIsStableAcrossRenameAndDistinctPerFile(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Path b = Files.writeString(dir.resolve("b.txt"), "b");
        FileKey ka = keyOf(a), kb = keyOf(b);
        assertNotEquals(ka, kb);
        assertTrue(ka.fileId().matches("[0-9A-F]{16}"), ka.fileId());
        Path renamed = Files.move(a, dir.resolve("renamed.txt"));
        assertEquals(ka, keyOf(renamed));
        assertEquals(ka, FileKey.parse(ka.asString()));
    }

    @Test
    void hardLinkCountAndAllocationRounding(@TempDir Path dir) throws IOException {
        Path f = Files.write(dir.resolve("f.bin"), new byte[5000]);
        assertEquals(1, platform.fileInspector().inspect(f).linkCount());
        Path link = dir.resolve("link.bin");
        Files.createLink(link, f);
        NativeFileState s = platform.fileInspector().inspect(f);
        assertEquals(2, s.linkCount());
        assertEquals(2, platform.fileInspector().inspect(link).linkCount());
        long cluster = platform.fileInspector().volumeOf(dir).clusterSize();
        assertEquals((5000 + cluster - 1) / cluster * cluster, s.allocatedSize());
        assertTrue(s.allocationExact());
        assertFalse(s.compressed());
        assertEquals(0, s.alternateStreams());
        assertFalse(s.reparsePoint());
        assertFalse(s.cloudPlaceholder());
    }

    @Test
    void alternateStreamsAreCounted(@TempDir Path dir) throws IOException {
        Path f = Files.writeString(dir.resolve("ads.txt"), "main");
        HANDLE h = open(longName(f) + ":s", GENERIC_WRITE, 0, OPEN_ALWAYS, 0x80);
        try {
            byte[] data = "stream".getBytes(StandardCharsets.US_ASCII);
            assertTrue(K32.WriteFile(h, data, data.length, new IntByReference(), null));
        } finally {
            close(h);
        }
        assertEquals(1, platform.fileInspector().inspect(f).alternateStreams());
    }

    @Test
    void symlinkIsNotFollowed(@TempDir Path dir) throws IOException {
        Path target = Files.writeString(dir.resolve("t.txt"), "x");
        Path link = dir.resolve("sym.txt");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symlink creation not permitted: " + e);
        }
        NativeFileState s = platform.fileInspector().inspect(link);
        assertTrue(s.reparsePoint());
        assertNotEquals(keyOf(target), s.fileKey());
    }

    @Test
    void junctionIsReportedAsReparsePointWithoutFollowing(@TempDir Path dir) throws Exception {
        Path target = Files.createDirectory(dir.resolve("target"));
        Path junction = dir.resolve("junction");
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), target.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assumeTrue(p.waitFor() == 0 && Files.exists(junction), "junction creation failed");
        NativeFileState s = platform.fileInspector().inspect(junction);
        assertTrue(s.reparsePoint());
        assertNotEquals(keyOf(target), s.fileKey());
    }

    @Test
    void compressAndDecompressRoundTrip(@TempDir Path dir) throws Exception {
        assumeCompressionSupported(dir);
        Path f = Files.write(dir.resolve("big.txt"), compressibleBytes(4 << 20));
        String hash = sha256(f);
        FileKey key = keyOf(f);
        NativeFileState before = platform.fileInspector().inspect(f);
        assertFalse(before.compressed());
        assertFalse(platform.nativeCompression().isCompressed(f));

        platform.nativeCompression().setCompressed(f, key, true);
        NativeFileState after = platform.fileInspector().inspect(f);
        System.out.println("[native] allocated before=" + before.allocatedSize() + " after=" + after.allocatedSize());
        assertTrue(platform.nativeCompression().isCompressed(f));
        assertTrue(after.compressed());
        assertTrue(after.allocationExact());
        assertTrue(after.allocatedSize() < before.allocatedSize() / 4, "allocation " + after.allocatedSize());
        assertEquals(hash, sha256(f));
        assertEquals(key, after.fileKey());

        platform.nativeCompression().setCompressed(f, key, false);
        assertFalse(platform.nativeCompression().isCompressed(f));
        NativeFileState restored = platform.fileInspector().inspect(f);
        assertFalse(restored.compressed());
        assertEquals(before.allocatedSize(), restored.allocatedSize());
        assertEquals(hash, sha256(f));
    }

    @Test
    void wrongKeyIsRejectedAndFileUntouched(@TempDir Path dir) throws IOException {
        assumeCompressionSupported(dir);
        Path f = Files.write(dir.resolve("x.txt"), compressibleBytes(1 << 20));
        FileKey wrong = new FileKey("DEADBEEF", "0000000000000001");
        assertThrows(FileIdentityMismatchException.class, () -> platform.nativeCompression().setCompressed(f, wrong, true));
        assertFalse(platform.nativeCompression().isCompressed(f));
    }

    @Test
    void openWriterBlocksCompression(@TempDir Path dir) throws IOException {
        assumeCompressionSupported(dir);
        Path f = Files.write(dir.resolve("locked.txt"), compressibleBytes(1 << 20));
        FileKey key = keyOf(f);
        assertEquals(Boolean.FALSE, platform.fileInspector().isLockedByOtherProcess(f));
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.read();
            // Java opens with FILE_SHARE_READ|FILE_SHARE_WRITE, still a writer is enough to conflict with our
            // share=READ-only open.
            assertEquals(Boolean.TRUE, platform.fileInspector().isLockedByOtherProcess(f));
            assertThrows(FileLockedException.class, () -> platform.nativeCompression().setCompressed(f, key, true));
        }
        assertFalse(platform.nativeCompression().isCompressed(f));
        assertEquals(Boolean.FALSE, platform.fileInspector().isLockedByOtherProcess(f));
    }

    @Test
    void exclusiveJnaHandleBlocksCompression(@TempDir Path dir) throws IOException {
        assumeCompressionSupported(dir);
        Path f = Files.write(dir.resolve("excl.txt"), compressibleBytes(1 << 16));
        FileKey key = keyOf(f);
        HANDLE h = open(longName(f), GENERIC_READ | GENERIC_WRITE, 0, OPEN_EXISTING, 0);
        try {
            assertEquals(Boolean.TRUE, platform.fileInspector().isLockedByOtherProcess(f));
            assertThrows(FileLockedException.class, () -> platform.nativeCompression().setCompressed(f, key, true));
        } finally {
            close(h);
        }
    }

    @Test
    void missingFileIsNotReportedAsLocked(@TempDir Path dir) {
        assertNull(platform.fileInspector().isLockedByOtherProcess(dir.resolve("nope.txt")));
    }

    @Test
    void directoryIsRejected(@TempDir Path dir) throws IOException {
        assumeCompressionSupported(dir);
        FileKey dirKey = keyOf(dir);
        assertThrows(IOException.class, () -> platform.nativeCompression().setCompressed(dir, dirKey, true));
        assertFalse(platform.nativeCompression().isCompressed(dir));
    }

    @Test
    void timestampsSurviveCompression(@TempDir Path dir) throws Exception {
        assumeCompressionSupported(dir);
        FileTime oldWrite = FileTime.fromMillis(1_577_880_000_000L); // 2020-01-01T12:00:00Z
        FileTime oldAccess = FileTime.fromMillis(1_580_558_400_000L); // 2020-02-01T12:00:00Z

        // 1) Measurement: raw ioctl without our restore step.
        Path raw = Files.write(dir.resolve("raw.txt"), compressibleBytes(1 << 20));
        Files.getFileAttributeView(raw, BasicFileAttributeView.class).setTimes(oldWrite, oldAccess, null);
        new WindowsCompression(false).setCompressed(raw, keyOf(raw), true);
        BasicFileAttributes rawAfter = Files.readAttributes(raw, BasicFileAttributes.class);
        System.out.println("[native] raw FSCTL_SET_COMPRESSION: lastWrite " + oldWrite + " -> " + rawAfter.lastModifiedTime()
                + ", lastAccess " + oldAccess + " -> " + rawAfter.lastAccessTime());

        // 2) IdleStow implementation must leave both unchanged.
        Path f = Files.write(dir.resolve("kept.txt"), compressibleBytes(1 << 20));
        Files.getFileAttributeView(f, BasicFileAttributeView.class).setTimes(oldWrite, oldAccess, null);
        FileKey key = keyOf(f);
        platform.nativeCompression().setCompressed(f, key, true);
        BasicFileAttributes a1 = Files.readAttributes(f, BasicFileAttributes.class);
        assertEquals(oldWrite, a1.lastModifiedTime());
        assertEquals(oldAccess, a1.lastAccessTime());
        platform.nativeCompression().setCompressed(f, key, false);
        BasicFileAttributes a2 = Files.readAttributes(f, BasicFileAttributes.class);
        assertEquals(oldWrite, a2.lastModifiedTime());
        assertEquals(oldAccess, a2.lastAccessTime());
    }

    @Test
    void longPathsWork(@TempDir Path dir) throws IOException {
        Path d = dir;
        while (d.toString().length() < 280) {
            d = d.resolve("segment_" + "x".repeat(40));
        }
        Path file;
        try {
            Files.createDirectories(d);
            file = Files.writeString(d.resolve("f.txt"), "hello");
        } catch (IOException e) {
            assumeTrue(false, "long path not creatable here: " + e);
            return;
        }
        assertTrue(file.toString().length() > 260);
        NativeFileState s = platform.fileInspector().inspect(file);
        assertNotNull(s.fileKey());
        assertEquals(1, s.linkCount());
        assertNotNull(platform.fileInspector().volumeOf(file));
    }

    @Test
    void powerStatusDoesNotThrow() {
        assertNotNull(platform.powerStatus().onBattery());
    }

    @Test
    void startupRegistrationRoundTripLeavesNothingBehind() throws IOException {
        String valueName = "IdleStowTest-" + UUID.randomUUID();
        WindowsPlatform p = new WindowsPlatform(valueName);
        var s = p.startupRegistration();
        try {
            assertFalse(s.isRegistered());
            s.register(List.of("C:\\Program Files\\IdleStow\\IdleStow.exe", "--background"));
            assertTrue(s.isRegistered());
            assertEquals("\"C:\\Program Files\\IdleStow\\IdleStow.exe\" --background",
                    com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(
                            com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
                            "Software\\Microsoft\\Windows\\CurrentVersion\\Run", valueName));
            assertThrows(IOException.class, () -> s.register(List.of("a\"b")));
        } finally {
            s.unregister();
        }
        assertFalse(s.isRegistered());
    }

    @Test
    void protectedLocationsOfRealEnvironment() {
        var p = platform.protectedLocations();
        assertTrue(p.protectedDirectories().contains(Path.of(System.getenv("SystemRoot")).toAbsolutePath().normalize()));
        assertTrue(p.protectedDirectoryNames().contains("$recycle.bin"));
    }
}
