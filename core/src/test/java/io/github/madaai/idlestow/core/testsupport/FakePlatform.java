package io.github.madaai.idlestow.core.testsupport;

import io.github.madaai.idlestow.core.platform.FileInspector;
import io.github.madaai.idlestow.core.platform.FileKey;
import io.github.madaai.idlestow.core.platform.NativeCompression;
import io.github.madaai.idlestow.core.platform.NativeFileState;
import io.github.madaai.idlestow.core.platform.PlatformServices;
import io.github.madaai.idlestow.core.platform.PowerStatus;
import io.github.madaai.idlestow.core.platform.ProtectedLocations;
import io.github.madaai.idlestow.core.platform.StartupRegistration;
import io.github.madaai.idlestow.core.platform.VolumeInfo;
import io.github.madaai.idlestow.core.util.Sizes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic, OS-independent platform used to test the engine's state machines.
 *
 * <p>File identity is derived from the creation timestamp (stable across renames on all supported
 * filesystems). "Native compression" is simulated: compressed files report a quarter of their
 * cluster-rounded size as allocation. This is a MOCK — real NTFS behaviour is covered by the
 * native tests in the platform module.
 */
public final class FakePlatform implements PlatformServices {
    public volatile boolean nativeSupported = true;
    public volatile VolumeInfo.AccessTracking tracking = VolumeInfo.AccessTracking.ENABLED;
    public volatile Boolean onBattery = false;
    public final Set<String> compressed = ConcurrentHashMap.newKeySet();
    public final Set<String> locked = ConcurrentHashMap.newKeySet();
    public final Map<String, String> keyOverride = new ConcurrentHashMap<>();
    public volatile List<Path> protectedDirs = List.of();

    private final FileInspector inspector = new FileInspector() {
        @Override
        public VolumeInfo volumeOf(Path path) {
            return new VolumeInfo("FAKEVOL1", path.getRoot() == null ? "/" : path.getRoot().toString(), "FAKEFS",
                    nativeSupported, nativeSupported ? "" : "This test volume does not support native compression",
                    4096, false, false, tracking, "test volume");
        }

        @Override
        public NativeFileState inspect(Path path) throws IOException {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String id = keyOverride.getOrDefault(path.toAbsolutePath().normalize().toString(),
                    Long.toHexString(a.creationTime().to(TimeUnit.NANOSECONDS)) + "-" + Long.toHexString(a.size() >= 0 ? 0 : 1));
            FileKey key = new FileKey("FAKEVOL1", id);
            boolean c = compressed.contains(key.asString());
            long rounded = Sizes.roundUp(a.size(), 4096);
            long alloc = c ? Math.max(4096, rounded / 4) : rounded;
            return new NativeFileState(key, alloc, true, c, false, false, a.isSymbolicLink() || a.isOther(), false,
                    false, false, 1, 0);
        }

        @Override
        public Boolean isLockedByOtherProcess(Path path) {
            return locked.contains(path.toAbsolutePath().normalize().toString());
        }
    };

    private final NativeCompression nc = new NativeCompression() {
        @Override public boolean implemented() { return true; }
        @Override public boolean supportedOn(VolumeInfo volume) { return volume.nativeCompression(); }

        @Override
        public boolean isCompressed(Path file) throws IOException {
            return inspector.inspect(file).compressed();
        }

        @Override
        public void setCompressed(Path file, FileKey expectedKey, boolean compress) throws IOException {
            if (Boolean.TRUE.equals(inspector.isLockedByOtherProcess(file))) throw new FileLockedException("locked");
            NativeFileState st = inspector.inspect(file);
            if (!st.fileKey().equals(expectedKey)) throw new FileIdentityMismatchException("identity mismatch");
            if (Files.isDirectory(file)) throw new IOException("directory");
            if (compress) compressed.add(expectedKey.asString());
            else compressed.remove(expectedKey.asString());
        }
    };

    @Override public String name() { return "fake"; }
    @Override public FileInspector fileInspector() { return inspector; }
    @Override public NativeCompression nativeCompression() { return nc; }

    @Override
    public ProtectedLocations protectedLocations() {
        return new ProtectedLocations() {
            @Override public List<Path> protectedDirectories() { return protectedDirs; }
            @Override public List<String> protectedDirectoryNames() { return List.of(); }
        };
    }

    @Override public PowerStatus powerStatus() { return () -> Optional.ofNullable(onBattery); }
    @Override public StartupRegistration startupRegistration() { return StartupRegistration.UNSUPPORTED; }
}
