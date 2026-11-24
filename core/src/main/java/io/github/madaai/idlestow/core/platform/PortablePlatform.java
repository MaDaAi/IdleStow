package io.github.madaai.idlestow.core.platform;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java platform implementation. Provides read-only inspection using only
 * {@code java.nio}. It never claims capabilities it cannot verify:
 * native compression is unsupported, allocation size is reported as not exact,
 * and last-access reliability is {@link VolumeInfo.AccessTracking#UNKNOWN}.
 *
 * <p>Used for tests and as the fallback on platforms without a dedicated adapter.
 */
public class PortablePlatform implements PlatformServices {

    private final FileInspector inspector = new PortableInspector();

    @Override public String name() { return "portable"; }
    @Override public FileInspector fileInspector() { return inspector; }
    @Override public NativeCompression nativeCompression() { return NativeCompression.UNSUPPORTED; }
    @Override public ProtectedLocations protectedLocations() { return PORTABLE_PROTECTED; }
    @Override public PowerStatus powerStatus() { return PowerStatus.UNKNOWN; }
    @Override public StartupRegistration startupRegistration() { return StartupRegistration.UNSUPPORTED; }

    private static final ProtectedLocations PORTABLE_PROTECTED = new ProtectedLocations() {
        @Override public List<Path> protectedDirectories() { return List.of(); }
        @Override public List<String> protectedDirectoryNames() { return List.of(); }
    };

    /** java.nio based inspector shared by the platform adapters as a base. */
    public static class PortableInspector implements FileInspector {

        @Override
        public VolumeInfo volumeOf(Path path) throws IOException {
            FileStore store = Files.getFileStore(path);
            String type = store.type() == null ? "unknown" : store.type();
            String id = Integer.toHexString((store.name() + "|" + type).hashCode()).toUpperCase(Locale.ROOT);
            long block = -1;
            try {
                block = store.getBlockSize();
            } catch (UnsupportedOperationException | IOException ignored) {
                // block size unknown on this platform
            }
            return new VolumeInfo(id, store.name(), type, false,
                    "Native compression is not available on this platform/filesystem.",
                    block, store.isReadOnly(), false,
                    VolumeInfo.AccessTracking.UNKNOWN,
                    "Last-access tracking reliability cannot be determined on this platform.");
        }

        @Override
        public NativeFileState inspect(Path path) throws IOException {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            FileKey key = null;
            Object fk = a.fileKey();
            if (fk != null) {
                key = new FileKey("portable", fk.toString());
            }
            boolean special = !a.isRegularFile() && !a.isDirectory();
            int links = -1;
            try {
                Object n = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                if (n instanceof Integer i) links = i;
            } catch (UnsupportedOperationException | IllegalArgumentException | IOException ignored) {
                // not a unix filesystem view
            }
            return new NativeFileState(key, a.size(), false, false, false, false,
                    a.isSymbolicLink() || special, false, false, false, links, -1);
        }

        @Override
        public Boolean isLockedByOtherProcess(Path path) {
            return null;
        }
    }
}
