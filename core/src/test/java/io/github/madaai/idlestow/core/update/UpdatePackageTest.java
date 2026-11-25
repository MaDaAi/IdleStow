package io.github.madaai.idlestow.core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdatePackageTest {
    static final Version V = new Version(1, 1, 0);
    static final String TAG = "v1.1.0";
    static final String ZIP_NAME = UpdateChecker.zipName(V);
    static final URI ZIP_URL = URI.create("https://github.com/MaDaAi/IdleStow/releases/download/" + TAG + "/" + ZIP_NAME);
    static final URI SUMS_URL = URI.create("https://github.com/MaDaAi/IdleStow/releases/download/" + TAG + "/SHA256SUMS.txt");

    @TempDir Path tmp;

    // ---------- helpers ----------

    static byte[] jar(String implVersion) throws IOException {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (implVersion != null) m.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, implVersion);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (JarOutputStream jo = new JarOutputStream(bo, m)) {
            jo.putNextEntry(new ZipEntry("x.txt"));
            jo.write(1);
            jo.closeEntry();
        }
        return bo.toByteArray();
    }

    static Map<String, byte[]> goodEntries(String jarVersion) throws IOException {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("IdleStow/IdleStow.exe", "exe".getBytes(StandardCharsets.UTF_8));
        e.put("IdleStow/runtime/release", "JAVA_VERSION=21".getBytes(StandardCharsets.UTF_8));
        e.put("IdleStow/app/idlestow.jar", jar(jarVersion));
        return e;
    }

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream zo = new ZipOutputStream(bo)) {
            for (var en : entries.entrySet()) {
                zo.putNextEntry(new ZipEntry(en.getKey()));
                zo.write(en.getValue());
                zo.closeEntry();
            }
        }
        return bo.toByteArray();
    }

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    static UpdateChecker.Release release(long size) {
        return new UpdateChecker.Release(V, TAG, "https://github.com/MaDaAi/IdleStow/releases/tag/" + TAG, "", "",
                ZIP_NAME, ZIP_URL, size, SUMS_URL);
    }

    FakeTransport transportFor(byte[] zipBytes, String sumsHash) {
        String sums = sumsHash + "  " + ZIP_NAME + "\n";
        return new FakeTransport().put(ZIP_URL, zipBytes).put(SUMS_URL, sums.getBytes(StandardCharsets.UTF_8));
    }

    Path work() { return tmp.resolve("work"); }
    Path stage() { return tmp.resolve("parent").resolve("IdleStow.update"); }

    UpdatePackage.Staged prepare(FakeTransport t, long size) throws IOException {
        return new UpdatePackage(t).prepare(release(size), work(), stage(), n -> { }, () -> false);
    }

    /** Runs prepare for a zip with the given entries and asserts it is refused, leaving nothing behind. */
    void assertRefused(Map<String, byte[]> entries) throws Exception {
        byte[] z = zip(entries);
        assertThrows(IOException.class, () -> prepare(transportFor(z, sha(z)), z.length));
        assertFalse(Files.exists(stage()), "staging folder must be removed");
        assertFalse(Files.exists(work().resolve(ZIP_NAME)), "downloaded zip must be removed");
        assertFalse(Files.exists(work().resolve(ZIP_NAME + ".part")));
        // nothing escaped
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
        assertFalse(Files.exists(tmp.resolve("parent").resolve("evil.txt")));
        assertFalse(Files.exists(work().resolve("evil.txt")));
    }

    // ---------- expectedSha256 ----------

    static final String H1 = "a".repeat(64);
    static final String H2 = "B".repeat(64);

    @Test
    void sumsPlainAndBinaryLines() {
        assertEquals(H1, UpdatePackage.expectedSha256((H1 + "  f.zip\n").getBytes(), "f.zip").orElseThrow());
        assertEquals(H1, UpdatePackage.expectedSha256((H1 + " *f.zip\n").getBytes(), "f.zip").orElseThrow());
        assertEquals(H1, UpdatePackage.expectedSha256((H1 + "  f.zip").getBytes(), "f.zip").orElseThrow());
    }

    @Test
    void sumsCrlfAndOtherFilesAndCase() {
        String s = H2 + "  other.zip\r\n" + H1.toUpperCase() + " *f.zip\r\n" + H2 + "  last.txt\r\n";
        assertEquals(H1, UpdatePackage.expectedSha256(s.getBytes(), "f.zip").orElseThrow());
        assertEquals(H2.toLowerCase(), UpdatePackage.expectedSha256(s.getBytes(), "other.zip").orElseThrow());
    }

    @Test
    void sumsMissingOrMalformed() {
        String s = H1 + "  other.zip\n";
        assertTrue(UpdatePackage.expectedSha256(s.getBytes(), "f.zip").isEmpty());
        assertTrue(UpdatePackage.expectedSha256(new byte[0], "f.zip").isEmpty());
        // short hash, and name that merely ends with the wanted name
        assertTrue(UpdatePackage.expectedSha256(("abc  f.zip\n").getBytes(), "f.zip").isEmpty());
        assertTrue(UpdatePackage.expectedSha256((H1 + "  xf.zip\n").getBytes(), "f.zip").isEmpty());
        assertTrue(UpdatePackage.expectedSha256((H1 + "  f.zip.sig\n").getBytes(), "f.zip").isEmpty());
    }

    // ---------- prepare ----------

    @Test
    void prepareSuccess() throws Exception {
        byte[] z = zip(goodEntries("1.1.0"));
        UpdatePackage.Staged s = prepare(transportFor(z, sha(z)), z.length);
        assertEquals(stage().resolve("IdleStow"), s.folder());
        assertTrue(Files.isRegularFile(stage().resolve(UpdatePackage.MARKER)));
        assertTrue(Files.isRegularFile(s.folder().resolve("IdleStow.exe")));
        assertTrue(Files.isRegularFile(s.folder().resolve("runtime/release")));
        assertTrue(Files.isRegularFile(s.folder().resolve("app/idlestow.jar")));
        assertEquals(V, s.release().version());
        assertFalse(Files.exists(work().resolve(ZIP_NAME)), "zip is deleted after unpacking");
        assertFalse(Files.exists(work().resolve(ZIP_NAME + ".part")));
    }

    /** Regression: Windows PowerShell's Compress-Archive writes backslash names and directory entries. */
    @Test
    void backslashEntryNamesAndDirectoryEntriesFromCompressArchive() throws Exception {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("IdleStow/", new byte[0]);
        e.put("IdleStow/runtime/", new byte[0]);
        e.put("IdleStow/runtime/legal/", new byte[0]);
        e.put("IdleStow/runtime/legal/java.base/LICENSE", "license".getBytes(StandardCharsets.UTF_8));
        e.put("IdleStow/IdleStow.exe", "exe".getBytes(StandardCharsets.UTF_8));
        e.put("IdleStow/app/idlestow.jar", jar("1.1.0"));
        Map<String, byte[]> windows = new LinkedHashMap<>();
        e.forEach((k, v) -> windows.put(k.replace('/', '\\'), v)); // as Compress-Archive writes them
        byte[] z = zip(windows);
        UpdatePackage.Staged s = prepare(transportFor(z, sha(z)), z.length);
        assertTrue(Files.isDirectory(s.folder().resolve("runtime/legal")), "directory entries become directories");
        assertTrue(Files.isRegularFile(s.folder().resolve("runtime/legal/java.base/LICENSE")));
        assertTrue(Files.isRegularFile(s.folder().resolve("IdleStow.exe")));
    }

    @Test
    void prepareReplacesEarlierMarkedStaging() throws Exception {
        Files.createDirectories(stage());
        Files.writeString(stage().resolve(UpdatePackage.MARKER), "old");
        Files.writeString(stage().resolve("stale.txt"), "stale");
        byte[] z = zip(goodEntries("1.1.0"));
        prepare(transportFor(z, sha(z)), z.length);
        assertFalse(Files.exists(stage().resolve("stale.txt")));
    }

    @Test
    void prepareRefusesUnmarkedExistingStagingAndKeepsIt() throws Exception {
        Files.createDirectories(stage());
        Files.writeString(stage().resolve("precious.txt"), "mine");
        byte[] z = zip(goodEntries("1.1.0"));
        assertThrows(IOException.class, () -> prepare(transportFor(z, sha(z)), z.length));
        assertEquals("mine", Files.readString(stage().resolve("precious.txt")));
    }

    @Test
    void checksumMismatchDiscardsDownload() throws Exception {
        byte[] z = zip(goodEntries("1.1.0"));
        FakeTransport t = transportFor(z, "0".repeat(64));
        assertThrows(IOException.class, () -> prepare(t, z.length));
        assertFalse(Files.exists(work().resolve(ZIP_NAME + ".part")));
        assertFalse(Files.exists(work().resolve(ZIP_NAME)));
        assertFalse(Files.exists(stage()));
    }

    @Test
    void sumsFileNotListingZipIsRefused() throws Exception {
        byte[] z = zip(goodEntries("1.1.0"));
        FakeTransport t = new FakeTransport().put(ZIP_URL, z)
                .put(SUMS_URL, (sha(z) + "  other.zip\n").getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> prepare(t, z.length));
        assertFalse(Files.exists(stage()));
    }

    @Test
    void sizeMismatchIsRefused() throws Exception {
        byte[] z = zip(goodEntries("1.1.0"));
        // advertised size smaller than the real file (transport refuses to deliver more than that)
        assertThrows(IOException.class, () -> prepare(transportFor(z, sha(z)), z.length - 1));
        assertFalse(Files.exists(work().resolve(ZIP_NAME + ".part")));
        assertFalse(Files.exists(stage()));
        // advertised size larger than the real file: checksum is right but size is not
        assertThrows(IOException.class, () -> prepare(transportFor(z, sha(z)), z.length + 1));
        assertFalse(Files.exists(work().resolve(ZIP_NAME + ".part")));
        assertFalse(Files.exists(work().resolve(ZIP_NAME)));
        assertFalse(Files.exists(stage()));
    }

    @Test
    void traversalEntryIsRefused() throws Exception {
        Map<String, byte[]> e = goodEntries("1.1.0");
        e.put("IdleStow/../evil.txt", "x".getBytes());
        assertRefused(e);
    }

    @Test
    void entryOutsideIdleStowFolderIsRefused() throws Exception {
        Map<String, byte[]> e = goodEntries("1.1.0");
        e.put("evil.txt", "x".getBytes());
        assertRefused(e);
    }

    @Test
    void absoluteEntryIsRefused() throws Exception {
        Map<String, byte[]> e = goodEntries("1.1.0");
        e.put("/evil.txt", "x".getBytes());
        assertRefused(e);
        Map<String, byte[]> e2 = goodEntries("1.1.0");
        e2.put("C:/evil.txt", "x".getBytes());
        assertRefused(e2);
        Map<String, byte[]> e3 = goodEntries("1.1.0");
        e3.put("IdleStow/..\\evil.txt", "x".getBytes());
        assertRefused(e3);
    }

    @Test
    void jarWithDifferentVersionIsRefused() throws Exception {
        assertRefused(goodEntries("1.0.9"));
        assertRefused(goodEntries(null));
    }

    @Test
    void missingPiecesAreRefused() throws Exception {
        for (String missing : new String[]{"IdleStow/IdleStow.exe", "IdleStow/runtime/release",
                "IdleStow/app/idlestow.jar"}) {
            Map<String, byte[]> e = goodEntries("1.1.0");
            e.remove(missing);
            assertRefused(e);
        }
    }

    @Test
    void emptyZipIsRefused() throws Exception {
        assertRefused(new LinkedHashMap<>());
    }

    @Test
    void cancellationDuringDownloadStops() throws Exception {
        byte[] z = zip(goodEntries("1.1.0"));
        FakeTransport t = transportFor(z, sha(z));
        assertThrows(CancellationException.class,
                () -> new UpdatePackage(t).prepare(release(z.length), work(), stage(), n -> { }, () -> true));
        assertFalse(Files.exists(work().resolve(ZIP_NAME + ".part")));
        assertFalse(Files.exists(work().resolve(ZIP_NAME)));
        assertFalse(Files.exists(stage()));
    }

    @Test
    void cancellationDuringUnpackStopsAndCleans() throws Exception {
        byte[] z = zip(goodEntries("1.1.0"));
        FakeTransport t = transportFor(z, sha(z));
        t.ignoreCancel = true; // download completes; the unpack loop must notice the cancellation
        assertThrows(CancellationException.class,
                () -> new UpdatePackage(t).prepare(release(z.length), work(), stage(), n -> { }, () -> true));
        assertFalse(Files.exists(stage()));
        assertFalse(Files.exists(work().resolve(ZIP_NAME)));
    }

    // ---------- clearStaging ----------

    @Test
    void clearStagingRefusesUnmarkedFolder() throws Exception {
        Path d = tmp.resolve("someone-elses");
        Files.createDirectories(d.resolve("sub"));
        Files.writeString(d.resolve("a.txt"), "A");
        Files.writeString(d.resolve("sub/b.txt"), "B");
        assertThrows(IOException.class, () -> UpdatePackage.clearStaging(d));
        assertEquals("A", Files.readString(d.resolve("a.txt")));
        assertEquals("B", Files.readString(d.resolve("sub/b.txt")));
    }

    @Test
    void clearStagingDeletesMarkedFolder() throws Exception {
        Path d = tmp.resolve("stage");
        Files.createDirectories(d.resolve("IdleStow/app"));
        Files.writeString(d.resolve(UpdatePackage.MARKER), "1.1.0");
        Files.writeString(d.resolve("IdleStow/app/x.jar"), "j");
        UpdatePackage.clearStaging(d);
        assertFalse(Files.exists(d));
    }

    /** Regression: jpackage launchers are read-only, which Windows refuses to delete unless the flag is cleared. */
    @Test
    void clearStagingDeletesReadOnlyFiles() throws Exception {
        Path d = tmp.resolve("stage");
        Files.createDirectories(d.resolve("IdleStow"));
        Files.writeString(d.resolve(UpdatePackage.MARKER), "1.1.0");
        Path exe = Files.writeString(d.resolve("IdleStow/IdleStow.exe"), "exe");
        assertTrue(exe.toFile().setReadOnly());
        UpdatePackage.clearStaging(d);
        assertFalse(Files.exists(d));
    }

    @Test
    void clearStagingOnMissingFolderIsNoop() throws Exception {
        assertDoesNotThrow(() -> UpdatePackage.clearStaging(tmp.resolve("nope")));
    }

    @Test
    void clearStagingDoesNotTouchSiblings() throws Exception {
        Path d = tmp.resolve("stage");
        Path sibling = tmp.resolve("sibling");
        Files.createDirectories(d);
        Files.createDirectories(sibling);
        Files.writeString(d.resolve(UpdatePackage.MARKER), "x");
        Files.writeString(sibling.resolve("keep.txt"), "k");
        UpdatePackage.clearStaging(d);
        try (Stream<Path> s = Files.list(tmp)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().equals("sibling")));
        }
        assertEquals("k", Files.readString(sibling.resolve("keep.txt")));
    }
}
