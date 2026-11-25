package io.github.madaai.idlestow.core.policy;

import io.github.madaai.idlestow.core.platform.ProtectedLocations;
import io.github.madaai.idlestow.core.util.PathUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Mandatory, built-in protections. These cannot be overridden by any user policy.
 *
 * <p>They cover operating-system locations (from the platform adapter), application data,
 * executables, live databases, virtual machine disks, password vaults, development tooling
 * directories, game libraries, cloud-sync internals and IdleStow's own data.
 */
public final class ProtectedRules {

    /** Directory names protected wherever they appear (lower case). */
    static final Set<String> PROTECTED_DIR_NAMES = Set.of(
            // live development environments / tooling
            ".git", ".svn", ".hg", "node_modules", ".venv", "venv", "__pycache__", ".idea", ".vscode", ".gradle",
            ".m2", ".tox", ".mypy_cache", ".pytest_cache", ".cargo", ".rustup",
            // application storage and caches
            "appdata", ".cache", ".config", ".local", ".ssh", ".gnupg", ".mozilla",
            // games
            "steamapps", "steamlibrary", "epic games", "gog games", "riot games", "xboxgames",
            // containers / VMs
            ".docker", "virtualbox vms", ".vagrant",
            // cloud sync internals
            ".dropbox", ".dropbox.cache", ".tmp.drivedownload", ".tmp.driveupload", ".sync", ".stfolder",
            // system
            "$recycle.bin", "system volume information", "windowsapps",
            // IdleStow's own recovery store
            ".idlestow");

    /** Extensions never optimized (executables, live databases, VM disks, vaults, encrypted containers). */
    static final Set<String> PROTECTED_EXTENSIONS = Set.of(
            "exe", "dll", "sys", "drv", "ocx", "msi", "msp", "com", "scr", "cpl", "efi", "so", "dylib", "ko", "app",
            "vhd", "vhdx", "vmdk", "vdi", "qcow2", "qcow", "hdd", "avhd", "avhdx", "vmem", "vmsn", "vmss",
            "sqlite", "sqlite3", "db", "db-wal", "db-shm", "db-journal", "mdf", "ldf", "ndf", "ldb", "mdb", "accdb",
            "frm", "ibd", "myd", "pst", "ost",
            "kdbx", "kdb", "1pif", "opvault", "agilekeychain", "keychain",
            "tc", "hc", "lnk", "url");

    static final Set<String> PROTECTED_FILE_NAMES = Set.of(
            "pagefile.sys", "hiberfil.sys", "swapfile.sys", "ntuser.dat", "ntuser.dat.log1", "ntuser.dat.log2",
            "desktop.ini", "thumbs.db", ".ds_store");

    private final List<Path> protectedDirs;
    /** Pre-normalized form of {@link #protectedDirs}, so the per-file check does not re-normalize every directory. */
    private final List<Path> protectedDirsNormalized;
    /** Windows: lower-case "dir" and "dir\\" strings for case-insensitive prefix tests. */
    private final List<String[]> protectedDirKeys;
    private final Set<String> platformDirNames;
    private final List<Path> cloudSyncRoots;

    public ProtectedRules(ProtectedLocations platform, Path dataDir) {
        List<Path> dirs = new ArrayList<>(platform.protectedDirectories());
        if (dataDir != null) dirs.add(PathUtil.normalize(dataDir));
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            Path h = Path.of(home);
            for (String sub : List.of("AppData", "Library", ".config", ".local", ".cache")) dirs.add(h.resolve(sub));
        }
        this.protectedDirs = List.copyOf(dirs);
        List<Path> norm = new ArrayList<>(dirs.size());
        List<String[]> keys = new ArrayList<>(dirs.size());
        for (Path d : dirs) {
            Path n = PathUtil.normalize(d);
            norm.add(n);
            String ps = n.toString().toLowerCase(Locale.ROOT);
            keys.add(new String[]{ps, ps.endsWith("\\") ? ps : ps + "\\"});
        }
        this.protectedDirsNormalized = List.copyOf(norm);
        this.protectedDirKeys = List.copyOf(keys);
        this.platformDirNames = Set.copyOf(platform.protectedDirectoryNames().stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).toList());
        this.cloudSyncRoots = detectCloudSyncRoots(home);
    }

    private static List<Path> detectCloudSyncRoots(String home) {
        List<Path> roots = new ArrayList<>();
        for (String env : List.of("OneDrive", "OneDriveConsumer", "OneDriveCommercial")) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) roots.add(PathUtil.normalize(Path.of(v)));
        }
        if (home != null && !home.isBlank()) {
            for (String n : List.of("Dropbox", "Google Drive", "My Drive", "iCloudDrive", "Box", "pCloudDrive")) {
                roots.add(PathUtil.normalize(Path.of(home, n)));
            }
        }
        return List.copyOf(roots);
    }

    /** Returns the reason a path is mandatorily protected, or empty when it is not. */
    public Optional<String> protectionReason(Path path, boolean directory) {
        Path p = PathUtil.normalize(path);
        // Same containment test as PathUtil.isWithin, with the file path normalized once instead of once per directory.
        String lower = PathUtil.isWindows() ? p.toString().toLowerCase(Locale.ROOT) : null;
        for (int i = 0; i < protectedDirs.size(); i++) {
            boolean within;
            if (lower != null) {
                String[] k = protectedDirKeys.get(i);
                within = lower.equals(k[0]) || lower.startsWith(k[1]);
            } else {
                within = p.startsWith(protectedDirsNormalized.get(i));
            }
            if (within) return Optional.of("Protected system or application location (" + protectedDirs.get(i) + ")");
        }
        for (Path part : p) {
            String n = part.toString().toLowerCase(Locale.ROOT);
            if (PROTECTED_DIR_NAMES.contains(n) || platformDirNames.contains(n)) {
                return Optional.of("Inside a protected folder type ('" + part + "')");
            }
        }
        if (!directory && p.getFileName() != null) {
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (PROTECTED_FILE_NAMES.contains(name)) return Optional.of("Protected system file");
            String ext = PathUtil.extension(name);
            if (PROTECTED_EXTENSIONS.contains(ext)) {
                return Optional.of("Protected file type (." + ext + "): executables, live databases, virtual disks,"
                        + " password vaults and encrypted containers are never optimized");
            }
        }
        return Optional.empty();
    }

    /** Whether the path lies inside a known cloud-synchronization folder. */
    public boolean inCloudSyncFolder(Path path) {
        for (Path r : cloudSyncRoots) if (PathUtil.isWithin(path, r)) return true;
        return false;
    }

    /** Whether the file is an archive produced by IdleStow (never re-optimized). */
    public static boolean isIdleStowArchive(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".idlestow.zip") || n.endsWith(".idlestow.tar.zst") || n.contains(".idlestow-tmp-");
    }
}
