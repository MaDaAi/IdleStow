package io.github.madaai.idlestow.platform.macos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MacPlatformTest {

    @Test
    void plistEscapesArguments() throws IOException {
        String p = MacPlatform.plist(List.of("/Applications/Idle & Stow.app/bin", "a<b>\"'"));
        assertTrue(p.contains("<string>/Applications/Idle &amp; Stow.app/bin</string>"));
        assertTrue(p.contains("<string>a&lt;b&gt;&quot;&apos;</string>"));
        assertTrue(p.contains("<key>RunAtLoad</key>"));
    }

    @Test
    void startupRoundTrip(@TempDir Path dir) throws IOException {
        MacPlatform p = new MacPlatform(dir);
        var s = p.startupRegistration();
        assertFalse(s.isRegistered());
        s.register(List.of("/usr/local/bin/idlestow"));
        assertTrue(Files.exists(dir.resolve("io.github.madaai.idlestow.plist")));
        assertTrue(s.isRegistered());
        s.unregister();
        assertFalse(s.isRegistered());
        assertEquals("macos", p.name());
        assertTrue(p.powerStatus().onBattery().isEmpty());
    }
}
