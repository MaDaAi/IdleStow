package io.github.madaai.idlestow.platform.linux;

import io.github.madaai.idlestow.core.platform.FileInspector;
import io.github.madaai.idlestow.core.platform.FileKey;
import io.github.madaai.idlestow.core.platform.NativeCompression;
import io.github.madaai.idlestow.core.platform.NativeFileState;
import io.github.madaai.idlestow.core.platform.PlatformServices;
import io.github.madaai.idlestow.core.platform.PortablePlatform;
import io.github.madaai.idlestow.core.platform.PowerStatus;
import io.github.madaai.idlestow.core.platform.ProtectedLocations;
import io.github.madaai.idlestow.core.platform.StartupRegistration;
import io.github.madaai.idlestow.core.platform.VolumeInfo;
import io.github.madaai.idlestow.core.platform.VolumeInfo.AccessTracking;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Linux platform services. <b>Implemented but not verified</b> on a real Linux system (developed on Windows; only
 * the pure parsers are unit tested).
 */
public final class LinuxPlatform implements PlatformServices {

    private final LinuxInspector inspector = new LinuxInspector(Path.of("/proc/self/mountinfo"));
    private final ProtectedLocations protectedLocations = new ProtectedLocations() {
        @Override public List<Path> protectedDirectories() {
            return Stream.of("/bin", "/boot", "/dev", "/etc", "/lib", "/lib64", "/proc", "/run", "/sbin", "/sys",
                    "/usr", "/var", "/snap", "/opt").map(Path::of).toList();
        }
        @Override public List<String> protectedDirectoryNames() { return List.of(); }
    };
    private final StartupRegistration startup;
    private final PowerStatus power;

    public LinuxPlatform() {
        this(defaultAutostartDir(), Path.of("/sys/class/power_supply"));
    }

    /** Test constructor with injectable locations. */
    LinuxPlatform(Path autostartDir, Path powerSupplyDir) {
        this.startup = new LinuxStartup(autostartDir);
        this.power = () -> mainsOnline(powerSupplyDir);
    }

    private static Path defaultAutostartDir() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("autostart");
    }

    @Override public String name() { return "linux"; }
    @Override public FileInspector fileInspector() { return inspector; }
    @Override public NativeCompression nativeCompression() { return NativeCompression.UNSUPPORTED; }
    @Override public ProtectedLocations protectedLocations() { return protectedLocations; }
    @Override public PowerStatus powerStatus() { return power; }
    @Override public StartupRegistration startupRegistration() { return startup; }

    // ------------------------------------------------------------------ power

    static Optional<Boolean> mainsOnline(Path dir) {
        List<Boolean> mains = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path supply : (Iterable<Path>) s::iterator) {
                try {
                    String type = Files.readString(supply.resolve("type")).trim();
                    if (!"Mains".equalsIgnoreCase(type)) continue;
                    mains.add("1".equals(Files.readString(supply.resolve("online")).trim()));
                } catch (IOException ignored) {
                    // unreadable supply: skip
                }
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        return onBatteryFromMains(mains);
    }

    /** No mains supply known: unknown. Any mains online: not on battery. Otherwise on battery. */
    static Optional<Boolean> onBatteryFromMains(List<Boolean> mainsOnline) {
        if (mainsOnline.isEmpty()) return Optional.empty();
        return Optional.of(!mainsOnline.contains(Boolean.TRUE));
    }

    // ------------------------------------------------------------------ mountinfo parsing

    /** One line of /proc/self/mountinfo. */
    record MountEntry(String majorMinor, String mountPoint, Set<String> mountOptions, String fsType,
                      String source, Set<String> superOptions) { }

    static List<MountEntry> parseMountInfo(List<String> lines) {
        List<MountEntry> out = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.trim().split(" ");
            int dash = -1;
            for (int i = 6; i < f.length; i++) {
                if (f[i].equals("-")) { dash = i; break; }
            }
            if (f.length < 7 || dash < 0 || f.length < dash + 3) continue;
            out.add(new MountEntry(f[2], unescape(f[4]), Set.of(f[5].split(",")), f[dash + 1], unescape(f[dash + 2]),
                    f.length > dash + 3 ? Set.of(f[dash + 3].split(",")) : Set.of()));
        }
        return out;
    }

    /** Decodes the octal escapes (\040 for space etc.) used in mountinfo paths. */
    static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 3 < s.length() && s.substring(i + 1, i + 4).matches("[0-7]{3}")) {
                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 4), 8));
                i += 3;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** The mount with the longest mount point that is a path-prefix of {@code path} (later entries win ties). */
    static MountEntry findMount(List<MountEntry> mounts, String path) {
        MountEntry best = null;
        for (MountEntry m : mounts) {
            String mp = m.mountPoint();
            boolean prefix = mp.equals("/") || path.equals(mp) || path.startsWith(mp.endsWith("/") ? mp : mp + "/");
            if (prefix && (best == null || mp.length() >= best.mountPoint().length())) best = m;
        }
        return best;
    }

    static AccessTracking accessTrackingOf(Set<String> options) {
        if (options.contains("noatime")) return AccessTracking.DISABLED;
        if (options.contains("relatime")) return AccessTracking.COARSE;
        return AccessTracking.ENABLED; // strictatime / atime / nothing listed = classic atime
    }

    static String accessNote(AccessTracking t) {
        return switch (t) {
            case DISABLED -> "Mounted with noatime: last-access times are not updated.";
            case COARSE -> "relatime: updated at most once per day";
            case ENABLED -> "Mounted with strict atime: last-access times are updated on every read.";
            default -> "Last-access tracking cannot be determined.";
        };
    }

    private static final Set<String> REMOTE_FS = Set.of("nfs", "nfs4", "cifs", "smb3", "smbfs", "afs", "9p", "ncpfs",
            "glusterfs", "ceph", "lustre", "fuse.sshfs", "fuse.rclone", "davfs");

    // ------------------------------------------------------------------ inspector

    static final class LinuxInspector extends PortablePlatform.PortableInspector {
        private final Path mountInfo;

        LinuxInspector(Path mountInfo) { this.mountInfo = mountInfo; }

        @Override
        public VolumeInfo volumeOf(Path path) throws IOException {
            Path real = path.toAbsolutePath().toRealPath();
            List<MountEntry> mounts = parseMountInfo(Files.readAllLines(mountInfo, StandardCharsets.UTF_8));
            MountEntry m = findMount(mounts, real.toString());
            if (m == null) return super.volumeOf(path);
            AccessTracking t = accessTrackingOf(m.mountOptions());
            long block = -1;
            try {
                block = Files.getFileStore(real).getBlockSize();
            } catch (IOException | UnsupportedOperationException ignored) {
                // unknown
            }
            String fs = m.fsType().toLowerCase(Locale.ROOT);
            boolean ro = m.mountOptions().contains("ro") || m.superOptions().contains("ro");
            return new VolumeInfo(m.majorMinor(), m.mountPoint(), m.fsType(), false,
                    "Native compression adapters for Linux filesystems (e.g. Btrfs) are planned, not implemented",
                    block, ro, REMOTE_FS.contains(fs), t, accessNote(t));
        }

        @Override
        public NativeFileState inspect(Path path) throws IOException {
            NativeFileState base = super.inspect(path);
            FileKey key = base.fileKey();
            try {
                Map<String, Object> a = Files.readAttributes(path, "unix:dev,ino,nlink", LinkOption.NOFOLLOW_LINKS);
                Object dev = a.get("dev"), ino = a.get("ino");
                if (dev instanceof Number d && ino instanceof Number i) {
                    key = new FileKey(Long.toHexString(d.longValue()).toUpperCase(Locale.ROOT), Long.toString(i.longValue()));
                }
            } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
                // keep portable key
            }
            return new NativeFileState(key, base.allocatedSize(), base.allocationExact(), base.compressed(),
                    base.encrypted(), base.sparse(), base.reparsePoint(), base.cloudPlaceholder(), base.offline(),
                    base.system(), base.linkCount(), base.alternateStreams());
        }
    }

    // ------------------------------------------------------------------ startup (XDG autostart)

    static final class LinuxStartup implements StartupRegistration {
        private final Path file;

        LinuxStartup(Path autostartDir) { this.file = autostartDir.resolve("idlestow.desktop"); }

        @Override public boolean supported() { return true; }
        @Override public boolean isRegistered() { return Files.isRegularFile(file); }

        @Override
        public void register(List<String> command) throws IOException {
            String content = desktopEntry(command);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }

        @Override public void unregister() throws IOException { Files.deleteIfExists(file); }
    }

    static String desktopEntry(List<String> command) throws IOException {
        if (command == null || command.isEmpty()) throw new IOException("Empty command");
        List<String> parts = new ArrayList<>();
        for (String a : command) parts.add(execQuote(a));
        return "[Desktop Entry]\nType=Application\nName=IdleStow\nComment=Idle files. Reclaimed space.\n"
                + "Exec=" + String.join(" ", parts) + "\nTerminal=false\nX-GNOME-Autostart-enabled=true\n";
    }

    /** Desktop Entry spec quoting for one Exec argument, including the second level of backslash escaping. */
    static String execQuote(String arg) throws IOException {
        if (arg == null || arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0 || arg.indexOf('\0') >= 0) {
            throw new IOException("Invalid argument");
        }
        String s = arg.replace("%", "%%");
        boolean quote = s.isEmpty() || s.chars().anyMatch(c -> " \t\"'\\<>~|&;$*?#()`".indexOf(c) >= 0);
        if (!quote) return s;
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '`' || c == '$' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        sb.append('"');
        return sb.toString().replace("\\", "\\\\");
    }
}
