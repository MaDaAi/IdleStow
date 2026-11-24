package io.github.madaai.idlestow.core.settings;

import io.github.madaai.idlestow.core.model.Enums.AuthorizationMode;
import io.github.madaai.idlestow.core.model.Enums.CloseBehavior;
import io.github.madaai.idlestow.core.model.Enums.CompressionProfile;
import io.github.madaai.idlestow.core.model.Enums.Confidence;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.PerformanceMode;
import io.github.madaai.idlestow.core.util.Sizes;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable, validated snapshot of all user settings.
 *
 * <p>Every value is parsed defensively: anything missing, malformed or out of range falls back to
 * its safe default. In particular, a damaged configuration can never enable autonomy — autonomy
 * additionally requires {@link #autonomyConfirmedDigest} to match the current autonomous policy.
 */
public record Settings(
        // General
        String theme,
        CloseBehavior closeBehavior,
        boolean notifications,
        boolean onboardingCompleted,
        // Optimization
        AuthorizationMode mode,
        String autonomyConfirmedDigest,
        int inactivityDays,
        int observationDays,
        Confidence minConfidenceAuto,
        Confidence minConfidenceRecommend,
        CompressionProfile profile,
        long minSavingsBytes,
        int minSavingsPercent,
        long minFileSize,
        long maxFileSize,
        Method preferredMethod,
        Method fallbackMethod,
        boolean archiveReplaceEnabled,
        int retentionDays,
        // Automation / background
        boolean backgroundEnabled,
        boolean startAtLogin,
        int scanIntervalHours,
        int autoIntervalHours,
        boolean watchEnabled,
        boolean autoOnBattery,
        int maxCpuPercent,
        boolean idleOnly,
        int idleMinutes,
        // Performance
        PerformanceMode performanceMode,
        int jobConcurrency,
        long maxSampleBytes,
        long minFreeReserveBytes,
        int maxBatchSize,
        long maxTempBytes,
        // Advanced
        boolean verboseLogging) {

    public static Settings defaults() {
        return fromMap(Map.of());
    }

    static final int[] THRESHOLD_PRESETS = {7, 14, 30, 60, 90, 180, 365};

    /** Builds settings from raw key/value pairs with full validation. */
    public static Settings fromMap(Map<String, String> m) {
        return new Settings(
                oneOf(m.get("ui.theme"), "dark", "dark", "light"),
                en(m.get("ui.closeBehavior"), CloseBehavior.class, CloseBehavior.EXIT),
                bool(m.get("ui.notifications"), true),
                bool(m.get("onboarding.completed"), false),
                en(m.get("auth.mode"), AuthorizationMode.class, AuthorizationMode.ASK),
                str(m.get("auth.autonomyDigest"), ""),
                intRange(m.get("policy.inactivityDays"), 30, 1, 3650),
                intRange(m.get("policy.observationDays"), 14, 0, 365),
                atLeastMedium(Confidence.parse(m.get("policy.minConfidenceAuto"), Confidence.HIGH)),
                Confidence.parse(m.get("policy.minConfidenceRecommend"), Confidence.MEDIUM),
                en(m.get("opt.profile"), CompressionProfile.class, CompressionProfile.BALANCED),
                longRange(m.get("opt.minSavingsBytes"), Sizes.MIB, 0, 1024 * Sizes.GIB),
                intRange(m.get("opt.minSavingsPercent"), 10, 0, 99),
                longRange(m.get("opt.minFileSize"), 64 * Sizes.KIB, 0, 1024 * Sizes.GIB),
                longRange(m.get("opt.maxFileSize"), 0, 0, Long.MAX_VALUE / 4),
                en(m.get("opt.preferredMethod"), Method.class, Method.NATIVE),
                en(m.get("opt.fallbackMethod"), Method.class, Method.ZIP_COPY),
                bool(m.get("opt.archiveReplaceEnabled"), false),
                intRange(m.get("recovery.retentionDays"), 14, 1, 3650),
                bool(m.get("bg.enabled"), false),
                bool(m.get("bg.startAtLogin"), false),
                intRange(m.get("bg.scanIntervalHours"), 6, 1, 168),
                intRange(m.get("bg.autoIntervalHours"), 24, 1, 168),
                bool(m.get("bg.watch"), true),
                bool(m.get("bg.autoOnBattery"), false),
                intRange(m.get("bg.maxCpuPercent"), 50, 5, 100),
                bool(m.get("bg.idleOnly"), false),
                intRange(m.get("bg.idleMinutes"), 10, 1, 240),
                en(m.get("perf.mode"), PerformanceMode.class, PerformanceMode.BALANCED),
                intRange(m.get("perf.jobConcurrency"), 1, 1, 4),
                longRange(m.get("perf.maxSampleBytes"), 4 * Sizes.MIB, 64 * Sizes.KIB, 64 * Sizes.MIB),
                longRange(m.get("perf.minFreeReserveBytes"), 2 * Sizes.GIB, 256 * Sizes.MIB, 1024 * Sizes.GIB),
                intRange(m.get("perf.maxBatchSize"), 500, 1, 100_000),
                longRange(m.get("perf.maxTempBytes"), 8 * Sizes.GIB, 64 * Sizes.MIB, 4096 * Sizes.GIB),
                bool(m.get("adv.verboseLogging"), false));
    }

    /** Serializes to raw key/value pairs (inverse of {@link #fromMap}). */
    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ui.theme", theme);
        m.put("ui.closeBehavior", closeBehavior.name());
        m.put("ui.notifications", Boolean.toString(notifications));
        m.put("onboarding.completed", Boolean.toString(onboardingCompleted));
        m.put("auth.mode", mode.name());
        m.put("auth.autonomyDigest", autonomyConfirmedDigest);
        m.put("policy.inactivityDays", Integer.toString(inactivityDays));
        m.put("policy.observationDays", Integer.toString(observationDays));
        m.put("policy.minConfidenceAuto", minConfidenceAuto.name());
        m.put("policy.minConfidenceRecommend", minConfidenceRecommend.name());
        m.put("opt.profile", profile.name());
        m.put("opt.minSavingsBytes", Long.toString(minSavingsBytes));
        m.put("opt.minSavingsPercent", Integer.toString(minSavingsPercent));
        m.put("opt.minFileSize", Long.toString(minFileSize));
        m.put("opt.maxFileSize", Long.toString(maxFileSize));
        m.put("opt.preferredMethod", preferredMethod.name());
        m.put("opt.fallbackMethod", fallbackMethod.name());
        m.put("opt.archiveReplaceEnabled", Boolean.toString(archiveReplaceEnabled));
        m.put("recovery.retentionDays", Integer.toString(retentionDays));
        m.put("bg.enabled", Boolean.toString(backgroundEnabled));
        m.put("bg.startAtLogin", Boolean.toString(startAtLogin));
        m.put("bg.scanIntervalHours", Integer.toString(scanIntervalHours));
        m.put("bg.autoIntervalHours", Integer.toString(autoIntervalHours));
        m.put("bg.watch", Boolean.toString(watchEnabled));
        m.put("bg.autoOnBattery", Boolean.toString(autoOnBattery));
        m.put("bg.maxCpuPercent", Integer.toString(maxCpuPercent));
        m.put("bg.idleOnly", Boolean.toString(idleOnly));
        m.put("bg.idleMinutes", Integer.toString(idleMinutes));
        m.put("perf.mode", performanceMode.name());
        m.put("perf.jobConcurrency", Integer.toString(jobConcurrency));
        m.put("perf.maxSampleBytes", Long.toString(maxSampleBytes));
        m.put("perf.minFreeReserveBytes", Long.toString(minFreeReserveBytes));
        m.put("perf.maxBatchSize", Integer.toString(maxBatchSize));
        m.put("perf.maxTempBytes", Long.toString(maxTempBytes));
        m.put("adv.verboseLogging", Boolean.toString(verboseLogging));
        return m;
    }

    /** Returns a copy with one raw key changed (validated through {@link #fromMap}). */
    public Settings with(String key, String value) {
        Map<String, String> m = toMap();
        if (!m.containsKey(key)) throw new IllegalArgumentException("Unknown setting " + key);
        m.put(key, value);
        return fromMap(m);
    }

    /** Autonomous operation never uses confidence below MEDIUM. */
    private static Confidence atLeastMedium(Confidence c) {
        return c.atLeast(Confidence.MEDIUM) ? c : Confidence.MEDIUM;
    }

    private static String str(String v, String d) { return v == null ? d : v; }

    private static String oneOf(String v, String d, String... allowed) {
        if (v == null) return d;
        for (String a : allowed) if (a.equalsIgnoreCase(v.trim())) return a;
        return d;
    }

    private static boolean bool(String v, boolean d) {
        if (v == null) return d;
        String t = v.trim().toLowerCase(Locale.ROOT);
        if (t.equals("true")) return true;
        if (t.equals("false")) return false;
        return d;
    }

    private static int intRange(String v, int d, int min, int max) {
        if (v == null) return d;
        try {
            int i = Integer.parseInt(v.trim());
            return i < min || i > max ? d : i;
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private static long longRange(String v, long d, long min, long max) {
        if (v == null) return d;
        try {
            long i = Long.parseLong(v.trim());
            return i < min || i > max ? d : i;
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private static <E extends Enum<E>> E en(String v, Class<E> type, E d) {
        if (v == null) return d;
        try {
            return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return d;
        }
    }
}
