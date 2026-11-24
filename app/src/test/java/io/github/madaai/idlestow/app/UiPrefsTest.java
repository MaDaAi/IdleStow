package io.github.madaai.idlestow.app;

import io.github.madaai.idlestow.app.util.UiPrefs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiPrefsTest {
    @Test
    void roundTrips(@TempDir Path dir) {
        UiPrefs p = UiPrefs.forDataDir(dir);
        p.put("win.w", 1111.5);
        p.put("win.max", true);
        p.put("view", "JOBS");
        p.save();
        UiPrefs q = UiPrefs.forDataDir(dir);
        assertEquals(1111.5, q.getDouble("win.w", 0));
        assertTrue(q.getBool("win.max", false));
        assertEquals("JOBS", q.getString("view", "x"));
        assertEquals(7, q.getDouble("missing", 7));
    }

    @Test
    void corruptFileFallsBackToDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ui-state.json"), "{ not json");
        UiPrefs p = UiPrefs.forDataDir(dir);
        assertFalse(p.has("view"));
        assertEquals("d", p.getString("view", "d"));
    }
}
