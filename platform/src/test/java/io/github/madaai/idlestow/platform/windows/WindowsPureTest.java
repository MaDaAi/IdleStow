package io.github.madaai.idlestow.platform.windows;

import io.github.madaai.idlestow.core.platform.VolumeInfo.AccessTracking;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-function tests for the Windows adapter; run on every OS. */
class WindowsPureTest {

    @Test
    void quotesArguments() throws IOException {
        assertEquals("C:\\a\\b.exe -x", WindowsPlatform.quoteCommand(List.of("C:\\a\\b.exe", "-x")));
        assertEquals("\"C:\\Program Files\\IdleStow\\i.exe\" --tray",
                WindowsPlatform.quoteCommand(List.of("C:\\Program Files\\IdleStow\\i.exe", "--tray")));
        assertEquals("x \"\"", WindowsPlatform.quoteCommand(List.of("x", "")));
        assertEquals("\"C:\\my dir\\\\\"", WindowsPlatform.quoteCommand(List.of("C:\\my dir\\")));
        assertThrows(IOException.class, () -> WindowsPlatform.quoteCommand(List.of("a\"b")));
        assertThrows(IOException.class, () -> WindowsPlatform.quoteCommand(List.of()));
    }

    @Test
    void mapsAccessTrackingRegistryValue() {
        assertEquals(AccessTracking.ENABLED, WindowsInspector.accessTrackingFromRegistry(0));
        assertEquals(AccessTracking.DISABLED, WindowsInspector.accessTrackingFromRegistry(1));
        assertEquals(AccessTracking.ENABLED, WindowsInspector.accessTrackingFromRegistry(0x80000002));
        assertEquals(AccessTracking.DISABLED, WindowsInspector.accessTrackingFromRegistry(0x80000001));
        assertTrue(WindowsInspector.trackingNoteFor(AccessTracking.ENABLED).contains("1 hour"));
    }

    @Test
    void mapsAcLineStatus() {
        assertEquals(Optional.of(true), WindowsPlatform.fromAcLineStatus(0));
        assertEquals(Optional.of(false), WindowsPlatform.fromAcLineStatus(1));
        assertEquals(Optional.empty(), WindowsPlatform.fromAcLineStatus(255));
    }

    @Test
    void longPathPrefix() {
        assertEquals("C:\\short", Win32.longName("C:\\short"));
        String longPath = "C:\\" + "a".repeat(250);
        assertEquals("\\\\?\\" + longPath, Win32.longName(longPath));
        String unc = "\\\\srv\\share\\" + "a".repeat(250);
        assertEquals("\\\\?\\UNC\\srv\\share\\" + "a".repeat(250), Win32.longName(unc));
    }

    @Test
    void roundsUpToCluster() {
        assertEquals(0, WindowsInspector.roundUp(0, 4096));
        assertEquals(4096, WindowsInspector.roundUp(1, 4096));
        assertEquals(8192, WindowsInspector.roundUp(4097, 4096));
    }

    @Test
    void protectedLocationsFromEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("SystemRoot", "C:\\Windows");
        env.put("ProgramFiles", "C:\\Program Files");
        env.put("ProgramData", "C:\\ProgramData");
        env.put("LOCALAPPDATA", "C:\\Users\\u\\AppData\\Local");
        env.put("APPDATA", "C:\\Users\\u\\AppData\\Roaming");
        var p = new WindowsPlatform.WindowsProtectedLocations(env::get);
        List<Path> dirs = p.protectedDirectories();
        assertTrue(dirs.contains(Path.of("C:\\Windows")) || !System.getProperty("os.name").startsWith("Windows"));
        assertFalse(dirs.isEmpty());
        assertTrue(p.protectedDirectoryNames().containsAll(
                List.of("$recycle.bin", "system volume information", "$windows.~bt", "$windows.~ws", "windowsapps")));
        assertFalse(p.protectedDirectoryNames().contains(".git"));
    }
}
