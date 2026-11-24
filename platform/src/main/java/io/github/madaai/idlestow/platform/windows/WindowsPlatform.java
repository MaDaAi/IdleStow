package io.github.madaai.idlestow.platform.windows;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import io.github.madaai.idlestow.core.platform.FileInspector;
import io.github.madaai.idlestow.core.platform.NativeCompression;
import io.github.madaai.idlestow.core.platform.PlatformServices;
import io.github.madaai.idlestow.core.platform.PowerStatus;
import io.github.madaai.idlestow.core.platform.ProtectedLocations;
import io.github.madaai.idlestow.core.platform.StartupRegistration;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static io.github.madaai.idlestow.platform.windows.Win32.K32;

/** Windows / NTFS platform services implemented with JNA. Verified on Windows 11 / NTFS. */
public final class WindowsPlatform implements PlatformServices {

    public static final String DEFAULT_RUN_VALUE = "IdleStow";

    private final WindowsInspector inspector;
    private final WindowsCompression compression;
    private final ProtectedLocations protectedLocations;
    private final StartupRegistration startup;

    /** Throws (Error or RuntimeException) if JNA / kernel32 cannot be loaded; see {@code Platforms.detect()}. */
    public WindowsPlatform() { this(DEFAULT_RUN_VALUE); }

    /** @param runValueName name of the HKCU Run value (configurable so tests never touch the real one) */
    public WindowsPlatform(String runValueName) {
        Win32.ensureLoaded();
        this.inspector = new WindowsInspector();
        this.compression = new WindowsCompression();
        this.protectedLocations = new WindowsProtectedLocations(System::getenv);
        this.startup = new WindowsStartup(runValueName);
    }

    @Override public String name() { return "windows"; }
    @Override public FileInspector fileInspector() { return inspector; }
    @Override public NativeCompression nativeCompression() { return compression; }
    @Override public ProtectedLocations protectedLocations() { return protectedLocations; }
    @Override public StartupRegistration startupRegistration() { return startup; }

    /** GetLastInputInfo / GetTickCount: milliseconds since the last keyboard or mouse input of this session. */
    @Override
    public java.util.OptionalLong userIdleMillis() {
        try {
            com.sun.jna.platform.win32.WinUser.LASTINPUTINFO info = new com.sun.jna.platform.win32.WinUser.LASTINPUTINFO();
            if (!com.sun.jna.platform.win32.User32.INSTANCE.GetLastInputInfo(info)) return java.util.OptionalLong.empty();
            int now = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetTickCount();
            long idle = Integer.toUnsignedLong(now - info.dwTime);
            return java.util.OptionalLong.of(idle);
        } catch (RuntimeException | LinkageError e) {
            return java.util.OptionalLong.empty();
        }
    }

    /** GetSystemPowerStatus: ACLineStatus 0 = battery, 1 = mains, 255 = unknown. */
    @Override
    public PowerStatus powerStatus() {
        return () -> {
            try {
                Memory m = new Memory(12);
                m.clear();
                if (!K32.GetSystemPowerStatus(m)) return Optional.empty();
                return fromAcLineStatus(m.getByte(0) & 0xFF);
            } catch (RuntimeException | LinkageError e) {
                return Optional.empty();
            }
        };
    }

    static Optional<Boolean> fromAcLineStatus(int acLineStatus) {
        return switch (acLineStatus) {
            case 0 -> Optional.of(true);
            case 1 -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    // ------------------------------------------------------------------ protected locations

    static final class WindowsProtectedLocations implements ProtectedLocations {
        private final List<Path> directories;

        WindowsProtectedLocations(Function<String, String> env) {
            Set<Path> dirs = new LinkedHashSet<>();
            for (String var : List.of("SystemRoot", "windir", "ProgramFiles", "ProgramFiles(x86)",
                    "ProgramW6432", "ProgramData")) {
                add(dirs, env.apply(var));
            }
            String systemRoot = env.apply("SystemRoot") != null ? env.apply("SystemRoot") : env.apply("windir");
            String systemDrive = env.apply("SystemDrive");
            if (systemDrive == null && systemRoot != null && systemRoot.length() >= 2 && systemRoot.charAt(1) == ':') {
                systemDrive = systemRoot.substring(0, 2);
            }
            if (systemDrive != null) {
                String root = systemDrive.endsWith("\\") ? systemDrive : systemDrive + "\\";
                for (String n : List.of("System Volume Information", "$Recycle.Bin", "Recovery", "Boot")) {
                    add(dirs, root + n);
                }
            }
            String local = env.apply("LOCALAPPDATA");
            if (local != null) {
                add(dirs, local + "\\Packages");
                add(dirs, local + "\\Microsoft");
            }
            String roaming = env.apply("APPDATA");
            if (roaming != null) add(dirs, roaming + "\\Microsoft");
            this.directories = List.copyOf(dirs);
        }

        private static void add(Set<Path> set, String s) {
            if (s == null || s.isBlank()) return;
            try {
                set.add(Path.of(s).toAbsolutePath().normalize());
            } catch (InvalidPathException ignored) {
                // skip malformed environment values
            }
        }

        @Override public List<Path> protectedDirectories() { return directories; }

        @Override public List<String> protectedDirectoryNames() {
            return List.of("$recycle.bin", "system volume information", "$windows.~bt", "$windows.~ws", "windowsapps");
        }
    }

    // ------------------------------------------------------------------ start at login

    static final class WindowsStartup implements StartupRegistration {
        private static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
        private final String valueName;

        WindowsStartup(String valueName) {
            if (valueName == null || valueName.isBlank()) throw new IllegalArgumentException("valueName");
            this.valueName = valueName;
        }

        @Override public boolean supported() { return true; }

        @Override
        public boolean isRegistered() throws IOException {
            try {
                return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, valueName);
            } catch (RuntimeException e) {
                throw new IOException("Cannot read Run key", e);
            }
        }

        @Override
        public void register(List<String> command) throws IOException {
            String line = quoteCommand(command);
            try {
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, valueName, line);
            } catch (RuntimeException e) {
                throw new IOException("Cannot write Run key", e);
            }
        }

        @Override
        public void unregister() throws IOException {
            try {
                if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, valueName)) {
                    Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, valueName);
                }
            } catch (RuntimeException e) {
                throw new IOException("Cannot delete Run value", e);
            }
        }
    }

    /**
     * Builds a command line: each argument is quoted when it is empty or contains whitespace; arguments containing
     * a double quote are rejected. Trailing backslashes of a quoted argument are doubled so they cannot escape the
     * closing quote.
     */
    static String quoteCommand(List<String> command) throws IOException {
        if (command == null || command.isEmpty()) throw new IOException("Empty command");
        List<String> parts = new ArrayList<>();
        for (String arg : command) {
            if (arg == null) throw new IOException("Null argument");
            if (arg.indexOf('"') >= 0) throw new IOException("Arguments containing a double quote are not supported: " + arg);
            if (arg.indexOf('\0') >= 0 || arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0) {
                throw new IOException("Control characters are not allowed in arguments");
            }
            boolean needsQuotes = arg.isEmpty() || arg.chars().anyMatch(Character::isWhitespace);
            if (!needsQuotes) {
                parts.add(arg);
                continue;
            }
            int trailing = 0;
            while (trailing < arg.length() && arg.charAt(arg.length() - 1 - trailing) == '\\') trailing++;
            parts.add('"' + arg + "\\".repeat(trailing) + '"');
        }
        return String.join(" ", parts);
    }
}
