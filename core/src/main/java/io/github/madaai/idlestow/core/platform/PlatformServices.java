package io.github.madaai.idlestow.core.platform;

/**
 * Aggregates every operating-system specific capability IdleStow needs.
 *
 * <p>Business logic depends only on this interface so it can be tested with
 * {@link PortablePlatform} or test doubles. Implementations must be thread safe.
 */
public interface PlatformServices {

    /** Short platform name, for example {@code "windows"}, {@code "linux"}, {@code "macos"}, {@code "portable"}. */
    String name();

    FileInspector fileInspector();

    NativeCompression nativeCompression();

    ProtectedLocations protectedLocations();

    PowerStatus powerStatus();

    StartupRegistration startupRegistration();

    /**
     * Milliseconds since the last user keyboard/mouse input, when the platform can report it.
     * Empty means "unknown"; callers requiring idleness must then treat the user as active.
     */
    default java.util.OptionalLong userIdleMillis() {
        return java.util.OptionalLong.empty();
    }
}
