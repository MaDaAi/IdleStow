package io.github.madaai.idlestow.core.update;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a release, verifies it and unpacks it next to the running application, ready to be swapped in.
 *
 * <p>Checks, in order: the SHA-256 of the downloaded zip must equal the value in the release's SHA256SUMS.txt; the
 * zip must contain exactly one top-level {@code IdleStow/} folder, no absolute or {@code ..} paths, at most
 * {@link #MAX_ENTRIES} entries and {@link #MAX_UNPACKED} bytes; the unpacked folder must contain IdleStow.exe,
 * app/idlestow.jar and a runtime; and the jar's Implementation-Version must equal the release version. Nothing in
 * the running installation is touched here.
 */
public final class UpdatePackage {
    static final int MAX_ENTRIES = 20_000;
    static final long MAX_UNPACKED = 2L << 30;
    static final long MAX_SUMS_BYTES = 64 << 10;
    private static final Pattern SUM_LINE = Pattern.compile("^([0-9a-fA-F]{64})\\s+\\*?(\\S.*?)\\s*$");

    private final UpdateTransport transport;

    public UpdatePackage(UpdateTransport transport) {
        this.transport = transport;
    }

    /** The verified, unpacked new version and where it lives. */
    public record Staged(UpdateChecker.Release release, Path folder) { }

    /**
     * @param workDir  IdleStow-owned scratch folder for the download (inside the data folder)
     * @param stageDir an empty or absent folder on the same volume as the application folder
     */
    public Staged prepare(UpdateChecker.Release r, Path workDir, Path stageDir, LongConsumer progress,
                          BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(workDir);
        String expected = expectedSha256(transport.get(r.sumsUrl(), MAX_SUMS_BYTES), r.zipName())
                .orElseThrow(() -> new IOException(UpdateChecker.SUMS_NAME + " does not list " + r.zipName()));

        Path part = workDir.resolve(r.zipName() + ".part");
        Path zip = workDir.resolve(r.zipName());
        Files.deleteIfExists(part);
        MessageDigest md = sha256();
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(part), md)) {
            transport.download(r.zipUrl(), out, r.zipSize(), progress, cancelled);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        String actual = HexFormat.of().formatHex(md.digest());
        if (!actual.equalsIgnoreCase(expected) || Files.size(part) != r.zipSize()) {
            Files.deleteIfExists(part);
            throw new IOException("The download did not match its published checksum, so it was discarded."
                    + " Nothing was changed.");
        }
        Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING);

        clearStaging(stageDir);
        Files.createDirectories(stageDir);
        Files.writeString(stageDir.resolve(MARKER), r.version().toString());
        try {
            unzip(zip, stageDir, cancelled);
            Path app = stageDir.resolve("IdleStow");
            validate(app, r.version());
            return new Staged(r, app);
        } catch (IOException | RuntimeException e) {
            clearStaging(stageDir);
            throw e;
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    static Optional<String> expectedSha256(byte[] sums, String fileName) {
        for (String line : new String(sums, StandardCharsets.UTF_8).split("\\R")) {
            Matcher m = SUM_LINE.matcher(line);
            if (m.matches() && m.group(2).equals(fileName)) return Optional.of(m.group(1).toLowerCase(Locale.ROOT));
        }
        return Optional.empty();
    }

    static void unzip(Path zip, Path dest, BooleanSupplier cancelled) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        int entries = 0;
        long total = 0;
        byte[] buf = new byte[1 << 16];
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Cancelled");
                if (++entries > MAX_ENTRIES) throw new IOException("Update package has too many files");
                String name = e.getName().replace('\\', '/');
                if (!name.startsWith("IdleStow/") || name.contains("../") || name.startsWith("/")
                        || name.contains(":") || name.contains("/./") || name.endsWith("/..")) {
                    throw new IOException("Update package contains an unexpected path: " + name);
                }
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) throw new IOException("Update package path escapes: " + name);
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        total += n;
                        if (total > MAX_UNPACKED) throw new IOException("Update package unpacks too large");
                        out.write(buf, 0, n);
                    }
                }
            }
        }
        if (entries == 0) throw new IOException("Update package is empty");
    }

    static void validate(Path app, Version expected) throws IOException {
        for (String required : new String[]{"IdleStow.exe", "app/idlestow.jar", "runtime"}) {
            if (!Files.exists(app.resolve(required), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Update package is incomplete (missing " + required + ")");
            }
        }
        try (JarFile jar = new JarFile(app.resolve("app/idlestow.jar").toFile())) {
            String v = jar.getManifest() == null ? null
                    : jar.getManifest().getMainAttributes().getValue("Implementation-Version");
            if (!expected.toString().equals(v)) {
                throw new IOException("Update package reports version " + v + ", expected " + expected);
            }
        }
    }

    /** Marker file proving a staging folder was created by IdleStow, so only such folders are ever deleted. */
    public static final String MARKER = ".idlestow-update-staging";

    /** Removes an earlier staging folder; refuses to delete a folder IdleStow did not create. */
    public static void clearStaging(Path stageDir) throws IOException {
        if (!Files.exists(stageDir, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(stageDir) || !Files.isRegularFile(stageDir.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(stageDir + " already exists and was not created by IdleStow's updater; move or"
                    + " delete it, then try again.");
        }
        deleteTree(stageDir);
    }

    /** Deletes a folder tree without following links. Callers must own the folder. */
    static void deleteTree(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
