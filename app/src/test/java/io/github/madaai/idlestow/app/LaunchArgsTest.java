package io.github.madaai.idlestow.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchArgsTest {
    @Test
    void defaultsAreAllOff() throws Exception {
        LaunchArgs a = LaunchArgs.parse(new String[0]);
        assertFalse(a.background());
        assertFalse(a.readOnly());
        assertFalse(a.smokeTest());
        assertNull(a.dataDir());
        assertNull(a.selfTestDir());
    }

    @Test
    void parsesFlagsAndDataDir() throws Exception {
        LaunchArgs a = LaunchArgs.parse(new String[]{"--background", "--read-only", "--data-dir", "some/dir"});
        assertTrue(a.background());
        assertTrue(a.readOnly());
        assertEquals(Path.of("some/dir").toAbsolutePath().normalize(), a.dataDir());
        LaunchArgs b = LaunchArgs.parse(new String[]{"--data-dir=x", "--smoke-test"});
        assertTrue(b.smokeTest());
        assertEquals(Path.of("x").toAbsolutePath().normalize(), b.dataDir());
    }

    @Test
    void parsesSelfTest() throws Exception {
        LaunchArgs a = LaunchArgs.parse(new String[]{"--self-test", "empty"});
        assertEquals(Path.of("empty").toAbsolutePath().normalize(), a.selfTestDir());
    }

    @Test
    void rejectsUnknownAndMissingValues() {
        assertThrows(LaunchArgs.ArgumentException.class, () -> LaunchArgs.parse(new String[]{"--bogus"}));
        assertThrows(LaunchArgs.ArgumentException.class, () -> LaunchArgs.parse(new String[]{"--data-dir"}));
        assertThrows(LaunchArgs.ArgumentException.class,
                () -> LaunchArgs.parse(new String[]{"--data-dir", "--background"}));
        assertThrows(LaunchArgs.ArgumentException.class, () -> LaunchArgs.parse(new String[]{"--self-test"}));
    }

    @Test
    void helpFlag() throws Exception {
        assertTrue(LaunchArgs.parse(new String[]{"--help"}).help());
    }
}
