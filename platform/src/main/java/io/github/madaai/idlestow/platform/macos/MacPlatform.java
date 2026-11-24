package io.github.madaai.idlestow.platform.macos;

import io.github.madaai.idlestow.core.platform.FileInspector;
import io.github.madaai.idlestow.core.platform.FileKey;
import io.github.madaai.idlestow.core.platform.NativeCompression;
import io.github.madaai.idlestow.core.platform.NativeFileState;
import io.github.madaai.idlestow.core.platform.PlatformServices;
import io.github.madaai.idlestow.core.platform.PortablePlatform;
import io.github.madaai.idlestow.core.platform.PowerStatus;
import io.github.madaai.idlestow.core.platform.ProtectedLocations;
import io.github.madaai.idlestow.core.platform.StartupRegistration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * macOS platform services. <b>Implemented but not verified</b> on a real Mac (developed on Windows; only the plist
 * generation is unit tested). Last-access tracking is reported as UNKNOWN, native compression is unsupported.
 */
public final class MacPlatform implements PlatformServices {

    static final String LABEL = "io.github.madaai.idlestow";

    private final FileInspector inspector = new MacInspector();
    private final ProtectedLocations protectedLocations = new ProtectedLocations() {
        @Override public List<Path> protectedDirectories() {
            return Stream.of("/System", "/Library", "/Applications", "/usr", "/bin", "/sbin", "/private",
                    "/Volumes/Recovery").map(Path::of).toList();
        }
        @Override public List<String> protectedDirectoryNames() { return List.of(); }
    };
    private final StartupRegistration startup;

    public MacPlatform() {
        this(Path.of(System.getProperty("user.home"), "Library", "LaunchAgents"));
    }

    /** Test constructor with an injectable LaunchAgents directory. */
    MacPlatform(Path launchAgentsDir) {
        this.startup = new MacStartup(launchAgentsDir.resolve(LABEL + ".plist"));
    }

    @Override public String name() { return "macos"; }
    @Override public FileInspector fileInspector() { return inspector; }
    @Override public NativeCompression nativeCompression() { return NativeCompression.UNSUPPORTED; }
    @Override public ProtectedLocations protectedLocations() { return protectedLocations; }
    @Override public PowerStatus powerStatus() { return PowerStatus.UNKNOWN; }
    @Override public StartupRegistration startupRegistration() { return startup; }

    static final class MacInspector extends PortablePlatform.PortableInspector {
        @Override
        public NativeFileState inspect(Path path) throws IOException {
            NativeFileState base = super.inspect(path);
            FileKey key = base.fileKey();
            try {
                Map<String, Object> a = Files.readAttributes(path, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
                if (a.get("dev") instanceof Number d && a.get("ino") instanceof Number i) {
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

    static final class MacStartup implements StartupRegistration {
        private final Path file;

        MacStartup(Path file) { this.file = file; }

        @Override public boolean supported() { return true; }
        @Override public boolean isRegistered() { return Files.isRegularFile(file); }

        @Override
        public void register(List<String> command) throws IOException {
            String plist = plist(command);
            Files.createDirectories(file.getParent());
            Files.writeString(file, plist, StandardCharsets.UTF_8);
        }

        @Override public void unregister() throws IOException { Files.deleteIfExists(file); }
    }

    static String plist(List<String> command) throws IOException {
        if (command == null || command.isEmpty()) throw new IOException("Empty command");
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
          .append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
          .append("<plist version=\"1.0\">\n<dict>\n")
          .append("  <key>Label</key>\n  <string>").append(LABEL).append("</string>\n")
          .append("  <key>ProgramArguments</key>\n  <array>\n");
        for (String a : command) {
            if (a == null) throw new IOException("Null argument");
            sb.append("    <string>").append(xmlEscape(a)).append("</string>\n");
        }
        sb.append("  </array>\n  <key>RunAtLoad</key>\n  <true/>\n</dict>\n</plist>\n");
        return sb.toString();
    }

    static String xmlEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
