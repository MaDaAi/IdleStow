package io.github.madaai.idlestow.core.update;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class VersionTest {
    @Test
    void parsesPlainAndPrefixed() {
        assertEquals(new Version(1, 2, 3), Version.parse("1.2.3").orElseThrow());
        assertEquals(new Version(1, 2, 3), Version.parse("v1.2.3").orElseThrow());
        assertEquals(new Version(10, 0, 21), Version.parse(" v10.0.21 ").orElseThrow());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"1.2", "1.2.3-beta", "x", "", "1.2.3.4", "V1.2.3", "1.2.x", "-1.2.3", "1.2.3+build",
            "1234567.0.0"})
    void rejectsNonVersions(String s) {
        assertTrue(Version.parse(s).isEmpty(), () -> "should reject " + s);
    }

    @Test
    void orderingAndNewerThan() {
        Version a = new Version(1, 2, 3);
        assertTrue(new Version(1, 2, 4).newerThan(a));
        assertTrue(new Version(1, 3, 0).newerThan(a));
        assertTrue(new Version(2, 0, 0).newerThan(a));
        assertFalse(a.newerThan(a));
        assertFalse(new Version(1, 2, 2).newerThan(a));
        assertFalse(new Version(0, 99, 99).newerThan(a));
        // numeric, not lexicographic
        assertTrue(new Version(1, 10, 0).newerThan(new Version(1, 9, 0)));
        assertEquals(0, a.compareTo(new Version(1, 2, 3)));
        assertTrue(a.compareTo(new Version(1, 2, 4)) < 0);
    }

    @Test
    void toStringHasNoPrefix() {
        assertEquals("1.2.3", new Version(1, 2, 3).toString());
        assertEquals("1.2.3", Version.parse("v1.2.3").orElseThrow().toString());
    }
}
