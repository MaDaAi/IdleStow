package io.github.madaai.idlestow.core.platform;

import java.io.IOException;
import java.util.List;

/** Per-user "start at login" registration. Never requires administrator rights. */
public interface StartupRegistration {
    boolean supported();

    boolean isRegistered() throws IOException;

    /** Registers {@code command} (executable followed by arguments) to start at user login. */
    void register(List<String> command) throws IOException;

    void unregister() throws IOException;

    StartupRegistration UNSUPPORTED = new StartupRegistration() {
        @Override public boolean supported() { return false; }
        @Override public boolean isRegistered() { return false; }
        @Override public void register(List<String> command) throws IOException {
            throw new IOException("Start at login is not supported on this platform");
        }
        @Override public void unregister() { }
    };
}
