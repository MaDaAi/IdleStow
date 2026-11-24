package io.github.madaai.idlestow.app;

import io.github.madaai.idlestow.app.util.Fmt;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FmtTest {
    @Test
    void sizesUseIecUnits() {
        assertEquals("1.00 KiB", Fmt.size(1024));
        assertEquals("1.50 MiB", Fmt.size(1536 * 1024L));
        assertEquals(Fmt.DASH, Fmt.size(-1));
        assertEquals(Fmt.DASH, Fmt.size((Long) null));
    }

    @Test
    void estimatesAreAlwaysMarked() {
        assertTrue(Fmt.estimate(2048L).startsWith(Fmt.APPROX));
        assertEquals(Fmt.DASH, Fmt.estimate(0L));
    }

    @Test
    void durations() {
        assertEquals("42 s", Fmt.duration(42_000));
        assertEquals("3 min 20 s", Fmt.duration(200_000));
        assertEquals("1 h 05 min", Fmt.duration(65 * 60_000L));
    }

    @Test
    void etaOnlyWhenComputable() {
        assertTrue(Fmt.eta(1000, 0).isEmpty());
        assertTrue(Fmt.eta(0, 100).isEmpty());
        assertEquals("10 s", Fmt.eta(1000, 100).orElseThrow());
    }

    @Test
    void julianDayConversion() {
        assertEquals(LocalDate.of(2024, 1, 1), Fmt.fromJulianDay(2460310));
    }

    @Test
    void labelsAndPaths() {
        assertEquals("Native compressed", Fmt.title("NATIVE_COMPRESSED"));
        assertEquals("file.txt", Fmt.fileName("C:\\a\\b\\file.txt"));
        String shortened = Fmt.middleEllipsis("abcdefghijklmnopqrstuvwxyz0123456789", 20);
        assertEquals(20, shortened.length());
        assertTrue(shortened.contains("…"));
        assertEquals("short", Fmt.middleEllipsis("short", 20));
        assertEquals("1 file", Fmt.plural(1, "file"));
        assertEquals("2 files", Fmt.plural(2, "file"));
    }
}
