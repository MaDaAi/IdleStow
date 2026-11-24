package io.github.madaai.idlestow.platform;

import io.github.madaai.idlestow.core.platform.PlatformServices;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class PlatformsTest {

    @Test
    void detectNeverThrowsAndMatchesOs() {
        PlatformServices p = Platforms.detect();
        assertNotNull(p);
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) assertEquals("windows", p.name());
        else if (os.contains("mac")) assertEquals("macos", p.name());
        else if (os.contains("linux")) assertEquals("linux", p.name());
        else assertEquals("portable", p.name());
        assertNotNull(p.fileInspector());
        assertNotNull(p.protectedLocations());
    }
}
