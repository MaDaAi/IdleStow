package io.github.madaai.idlestow.platform.linux;

import io.github.madaai.idlestow.core.platform.VolumeInfo.AccessTracking;
import io.github.madaai.idlestow.platform.linux.LinuxPlatform.MountEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LinuxPlatformTest {

    private static final List<String> MOUNTINFO = List.of(
            "22 1 259:2 / / rw,relatime shared:1 - ext4 /dev/nvme0n1p2 rw,errors=remount-ro",
            "30 22 8:17 / /mnt/data rw,noatime shared:5 - xfs /dev/sdb1 rw",
            "31 22 8:33 / /mnt/my\\040disk rw,strictatime - ext4 /dev/sdc1 rw",
            "32 22 0:50 / /mnt/share ro,relatime - nfs4 srv:/x ro,vers=4.2",
            "garbage");

    @Test
    void parsesMountInfoAndSkipsGarbage() {
        List<MountEntry> m = LinuxPlatform.parseMountInfo(MOUNTINFO);
        assertEquals(4, m.size());
        assertEquals("ext4", m.get(0).fsType());
        assertEquals("259:2", m.get(0).majorMinor());
        assertEquals("/mnt/my disk", m.get(2).mountPoint());
    }

    @Test
    void findsLongestMountPrefix() {
        List<MountEntry> m = LinuxPlatform.parseMountInfo(MOUNTINFO);
        assertEquals("/", LinuxPlatform.findMount(m, "/home/u/x").mountPoint());
        assertEquals("/mnt/data", LinuxPlatform.findMount(m, "/mnt/data/a/b").mountPoint());
        assertEquals("/mnt/data", LinuxPlatform.findMount(m, "/mnt/data").mountPoint());
        assertEquals("/", LinuxPlatform.findMount(m, "/mnt/database").mountPoint());
        assertEquals("/mnt/my disk", LinuxPlatform.findMount(m, "/mnt/my disk/f").mountPoint());
    }

    @Test
    void mapsAtimeOptions() {
        assertEquals(AccessTracking.DISABLED, LinuxPlatform.accessTrackingOf(Set.of("rw", "noatime")));
        assertEquals(AccessTracking.COARSE, LinuxPlatform.accessTrackingOf(Set.of("rw", "relatime")));
        assertEquals(AccessTracking.ENABLED, LinuxPlatform.accessTrackingOf(Set.of("rw", "strictatime")));
        assertEquals(AccessTracking.ENABLED, LinuxPlatform.accessTrackingOf(Set.of("rw")));
        assertTrue(LinuxPlatform.accessNote(AccessTracking.COARSE).contains("at most once per day"));
    }

    @Test
    void powerFromMains() {
        assertEquals(Optional.empty(), LinuxPlatform.onBatteryFromMains(List.of()));
        assertEquals(Optional.of(false), LinuxPlatform.onBatteryFromMains(List.of(false, true)));
        assertEquals(Optional.of(true), LinuxPlatform.onBatteryFromMains(List.of(false)));
    }

    @Test
    void powerSupplyDirectory(@TempDir Path dir) throws IOException {
        assertEquals(Optional.empty(), LinuxPlatform.mainsOnline(dir.resolve("missing")));
        Path ac = Files.createDirectories(dir.resolve("AC"));
        Files.writeString(ac.resolve("type"), "Mains\n");
        Files.writeString(ac.resolve("online"), "0\n");
        Path bat = Files.createDirectories(dir.resolve("BAT0"));
        Files.writeString(bat.resolve("type"), "Battery\n");
        assertEquals(Optional.of(true), LinuxPlatform.mainsOnline(dir));
        Files.writeString(ac.resolve("online"), "1\n");
        assertEquals(Optional.of(false), LinuxPlatform.mainsOnline(dir));
    }

    @Test
    void desktopEntryQuoting() throws IOException {
        assertEquals("simple", LinuxPlatform.execQuote("simple"));
        assertEquals("\"/opt/my app/x\"", LinuxPlatform.execQuote("/opt/my app/x"));
        assertEquals("100%%", LinuxPlatform.execQuote("100%"));
        assertThrows(IOException.class, () -> LinuxPlatform.execQuote("a\nb"));
        String e = LinuxPlatform.desktopEntry(List.of("/usr/bin/java", "-jar", "/opt/idle stow/app.jar"));
        assertTrue(e.contains("Exec=/usr/bin/java -jar \"/opt/idle stow/app.jar\"\n"));
    }

    @Test
    void startupRoundTrip(@TempDir Path dir) throws IOException {
        LinuxPlatform p = new LinuxPlatform(dir.resolve("autostart"), dir.resolve("ps"));
        var s = p.startupRegistration();
        assertFalse(s.isRegistered());
        s.register(List.of("/bin/idlestow", "--tray"));
        assertTrue(s.isRegistered());
        assertTrue(Files.exists(dir.resolve("autostart/idlestow.desktop")));
        s.unregister();
        assertFalse(s.isRegistered());
        assertFalse(p.nativeCompression().implemented());
    }
}
