package io.github.madaai.idlestow.core.settings;

import io.github.madaai.idlestow.core.model.Enums.AuthorizationMode;
import io.github.madaai.idlestow.core.model.Enums.CloseBehavior;
import io.github.madaai.idlestow.core.model.Enums.CompressionProfile;
import io.github.madaai.idlestow.core.model.Enums.Confidence;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.PerformanceMode;
import io.github.madaai.idlestow.core.util.Sizes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    @Test
    void defaultsAreSafe() {
        Settings s = Settings.defaults();
        assertEquals(AuthorizationMode.ASK, s.mode());
        assertEquals("", s.autonomyConfirmedDigest());
        assertEquals(30, s.inactivityDays());
        assertEquals(Confidence.HIGH, s.minConfidenceAuto());
        assertEquals(Confidence.MEDIUM, s.minConfidenceRecommend());
        assertFalse(s.backgroundEnabled());
        assertFalse(s.startAtLogin());
        assertFalse(s.archiveReplaceEnabled());
        assertEquals(Sizes.MIB, s.minSavingsBytes());
        assertEquals(10, s.minSavingsPercent());
        assertFalse(s.onboardingCompleted());
        assertEquals(Method.NATIVE, s.preferredMethod());
        assertEquals(Method.ZIP_COPY, s.fallbackMethod());
        assertEquals(CloseBehavior.EXIT, s.closeBehavior());
        assertEquals(CompressionProfile.BALANCED, s.profile());
        assertEquals(PerformanceMode.BALANCED, s.performanceMode());
        assertFalse(s.autoOnBattery());
        assertFalse(s.idleOnly());
    }

    @Test
    void emptyAndBlankMapsGiveDefaults() {
        assertEquals(Settings.defaults(), Settings.fromMap(Map.of()));
        Map<String, String> blanks = new HashMap<>();
        for (String k : Settings.defaults().toMap().keySet()) blanks.put(k, "");
        assertEquals(Settings.defaults(), Settings.fromMap(blanks));
    }

    /** key, default, below minimum, above maximum, min valid, max valid. */
    static Stream<Arguments> ranges() {
        return Stream.of(
                Arguments.of("policy.inactivityDays", "30", "0", "3651", "1", "3650"),
                Arguments.of("policy.observationDays", "14", "-1", "366", "0", "365"),
                Arguments.of("opt.minSavingsBytes", Long.toString(Sizes.MIB), "-1",
                        Long.toString(1024 * Sizes.GIB + 1), "0", Long.toString(1024 * Sizes.GIB)),
                Arguments.of("opt.minSavingsPercent", "10", "-1", "100", "0", "99"),
                Arguments.of("opt.minFileSize", Long.toString(64 * Sizes.KIB), "-1",
                        Long.toString(1024 * Sizes.GIB + 1), "0", Long.toString(1024 * Sizes.GIB)),
                Arguments.of("opt.maxFileSize", "0", "-1", Long.toString(Long.MAX_VALUE / 4 + 1), "0",
                        Long.toString(Long.MAX_VALUE / 4)),
                Arguments.of("recovery.retentionDays", "14", "0", "3651", "1", "3650"),
                Arguments.of("bg.scanIntervalHours", "6", "0", "169", "1", "168"),
                Arguments.of("bg.autoIntervalHours", "24", "0", "169", "1", "168"),
                Arguments.of("bg.maxCpuPercent", "50", "4", "101", "5", "100"),
                Arguments.of("bg.idleMinutes", "10", "0", "241", "1", "240"),
                Arguments.of("perf.jobConcurrency", "1", "0", "5", "1", "4"),
                Arguments.of("perf.maxSampleBytes", Long.toString(Sizes.MIB), Long.toString(64 * Sizes.KIB - 1),
                        Long.toString(64 * Sizes.MIB + 1), Long.toString(64 * Sizes.KIB), Long.toString(64 * Sizes.MIB)),
                Arguments.of("perf.minFreeReserveBytes", Long.toString(2 * Sizes.GIB),
                        Long.toString(256 * Sizes.MIB - 1), Long.toString(1024 * Sizes.GIB + 1),
                        Long.toString(256 * Sizes.MIB), Long.toString(1024 * Sizes.GIB)),
                Arguments.of("perf.maxBatchSize", "500", "0", "100001", "1", "100000"),
                Arguments.of("perf.maxTempBytes", Long.toString(8 * Sizes.GIB), Long.toString(64 * Sizes.MIB - 1),
                        Long.toString(4096 * Sizes.GIB + 1), Long.toString(64 * Sizes.MIB),
                        Long.toString(4096 * Sizes.GIB)));
    }

    private static String get(Settings s, String key) {
        return s.toMap().get(key);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ranges")
    void numericOutOfRangeFallsBackToDefault(String key, String dflt, String below, String above, String lo, String hi) {
        assertEquals(dflt, get(Settings.defaults(), key));
        for (String bad : new String[]{below, above, "abc", "", "  ", "1.5", "NaN", "99999999999999999999999",
                "-99999999999999999999999", "0x10", "1e3", "∞"}) {
            assertEquals(dflt, get(Settings.fromMap(Map.of(key, bad)), key), key + " <- '" + bad + "'");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ranges")
    void numericBoundariesAreAccepted(String key, String dflt, String below, String above, String lo, String hi) {
        assertEquals(lo, get(Settings.fromMap(Map.of(key, lo)), key));
        assertEquals(hi, get(Settings.fromMap(Map.of(key, hi)), key));
        assertEquals(lo, get(Settings.fromMap(Map.of(key, " " + lo + " ")), key), "whitespace is trimmed");
    }

    @Test
    void enumGarbageFallsBackToDefault() {
        for (String bad : new String[]{"", "nope", "AUTONOMOUS!", "0", "null", "Ask Before"}) {
            Settings s = Settings.fromMap(Map.of("auth.mode", bad, "ui.closeBehavior", bad, "opt.profile", bad,
                    "opt.preferredMethod", bad, "opt.fallbackMethod", bad, "perf.mode", bad));
            assertEquals(AuthorizationMode.ASK, s.mode(), bad);
            assertEquals(CloseBehavior.EXIT, s.closeBehavior());
            assertEquals(CompressionProfile.BALANCED, s.profile());
            assertEquals(Method.NATIVE, s.preferredMethod());
            assertEquals(Method.ZIP_COPY, s.fallbackMethod());
            assertEquals(PerformanceMode.BALANCED, s.performanceMode());
        }
    }

    @Test
    void enumsAreCaseInsensitiveAndTrimmed() {
        assertEquals(AuthorizationMode.MANUAL, Settings.fromMap(Map.of("auth.mode", " manual ")).mode());
        assertEquals(Method.ZIP_REPLACE, Settings.fromMap(Map.of("opt.preferredMethod", "zip_replace")).preferredMethod());
    }

    @Test
    void booleansOnlyAcceptTrueAndFalse() {
        for (String bad : new String[]{"yes", "1", "on", "TRUEISH", ""}) {
            Settings s = Settings.fromMap(Map.of("bg.enabled", bad, "bg.watch", bad, "ui.notifications", bad));
            assertFalse(s.backgroundEnabled(), bad);
            assertTrue(s.watchEnabled(), bad);
            assertTrue(s.notifications(), bad);
        }
        assertTrue(Settings.fromMap(Map.of("bg.enabled", " TRUE ")).backgroundEnabled());
        assertFalse(Settings.fromMap(Map.of("bg.watch", "False")).watchEnabled());
    }

    @Test
    void themeIsRestricted() {
        assertEquals("dark", Settings.defaults().theme());
        assertEquals("light", Settings.fromMap(Map.of("ui.theme", " LIGHT ")).theme());
        assertEquals("dark", Settings.fromMap(Map.of("ui.theme", "<script>")).theme());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "LOW", "none", "low", " low "})
    void minConfidenceAutoNeverBelowMedium(String value) {
        assertEquals(Confidence.MEDIUM, Settings.fromMap(Map.of("policy.minConfidenceAuto", value)).minConfidenceAuto());
        assertEquals(Confidence.MEDIUM, Settings.defaults().with("policy.minConfidenceAuto", value).minConfidenceAuto());
    }

    @Test
    void minConfidenceAutoKeepsMediumAndHighAndRejectsGarbage() {
        assertEquals(Confidence.MEDIUM, Settings.fromMap(Map.of("policy.minConfidenceAuto", "MEDIUM")).minConfidenceAuto());
        assertEquals(Confidence.HIGH, Settings.fromMap(Map.of("policy.minConfidenceAuto", "HIGH")).minConfidenceAuto());
        assertEquals(Confidence.HIGH, Settings.fromMap(Map.of("policy.minConfidenceAuto", "bogus")).minConfidenceAuto());
    }

    @Test
    void withUnknownKeyThrows() {
        assertThrows(IllegalArgumentException.class, () -> Settings.defaults().with("does.not.exist", "1"));
        assertThrows(IllegalArgumentException.class, () -> Settings.defaults().with("", "1"));
        assertThrows(IllegalArgumentException.class, () -> Settings.defaults().with("auth.MODE", "MANUAL"));
    }

    @Test
    void withValidatesThroughFromMap() {
        Settings s = Settings.defaults().with("policy.inactivityDays", "90");
        assertEquals(90, s.inactivityDays());
        assertEquals(30, s.with("policy.inactivityDays", "0").inactivityDays());
        assertEquals(Settings.defaults(), Settings.defaults().with("auth.mode", "x"));
    }

    @Test
    void toMapFromMapRoundTrip() {
        Settings custom = Settings.defaults().with("auth.mode", "AUTONOMOUS").with("auth.autonomyDigest", "abc123")
                .with("policy.inactivityDays", "180").with("policy.minConfidenceAuto", "MEDIUM")
                .with("bg.enabled", "true").with("bg.startAtLogin", "true").with("opt.archiveReplaceEnabled", "true")
                .with("ui.theme", "light").with("perf.jobConcurrency", "3").with("opt.maxFileSize", "123456789")
                .with("opt.preferredMethod", "ZIP_COPY").with("perf.mode", "SILENT");
        assertEquals(custom, Settings.fromMap(custom.toMap()));
        assertEquals(custom.toMap(), Settings.fromMap(custom.toMap()).toMap());
        assertEquals(Settings.defaults(), Settings.fromMap(Settings.defaults().toMap()));
    }

    @Test
    void toMapCoversEveryFieldWithAUniqueKey() {
        Map<String, String> m = Settings.defaults().toMap();
        assertEquals(Settings.class.getRecordComponents().length, m.size());
    }

    @Test
    void damagedConfigWithAutonomousModeKeepsDigestEmpty() {
        Settings s = Settings.fromMap(Map.of("auth.mode", "AUTONOMOUS"));
        assertEquals(AuthorizationMode.AUTONOMOUS, s.mode());
        assertEquals("", s.autonomyConfirmedDigest());
    }
}
