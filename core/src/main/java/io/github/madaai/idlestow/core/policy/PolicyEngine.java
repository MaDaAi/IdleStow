package io.github.madaai.idlestow.core.policy;

import io.github.madaai.idlestow.core.db.RulesRepository;
import io.github.madaai.idlestow.core.db.RulesRepository.Exclusion;
import io.github.madaai.idlestow.core.model.Enums.Confidence;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.settings.Settings;
import io.github.madaai.idlestow.core.util.PathUtil;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves protections and effective policies.
 *
 * <p>Precedence (most authoritative first):
 * <ol>
 *   <li>Mandatory protections: protected OS/application locations and file types ({@link ProtectedRules})</li>
 *   <li>Authorization boundary: the path must lie inside an active (non-revoked) monitored root</li>
 *   <li>Explicit pins and user exclusions</li>
 *   <li>File-specific policy</li>
 *   <li>Directory policy (deepest directory wins)</li>
 *   <li>File-type (extension) policy</li>
 *   <li>Monitored-root policy (and the root's archival flag)</li>
 *   <li>Global policy, then application settings defaults</li>
 * </ol>
 * Levels 1–3 are restrictions that no policy can loosen. Levels 4–8 resolve each field independently:
 * the first level that sets a field wins.
 */
public final class PolicyEngine {

    private final RulesRepository rules;
    private final ProtectedRules protectedRules;
    private volatile Snapshot snapshot;

    public PolicyEngine(RulesRepository rules, ProtectedRules protectedRules) throws SQLException {
        this.rules = rules;
        this.protectedRules = protectedRules;
        reload();
    }

    /** Reloads rules from the database (call after any change to exclusions, pins or policies). */
    public synchronized void reload() throws SQLException {
        List<Exclusion> ex = rules.exclusions();
        Set<String> pins = new HashSet<>();
        for (RulesRepository.Pin p : rules.pins()) pins.add(p.pathKey());
        List<PolicyRule> pol = new ArrayList<>(rules.policies());
        List<PathMatcher> globs = new ArrayList<>();
        for (Exclusion e : ex) {
            if (e.kind().equals("GLOB")) {
                try {
                    globs.add(FileSystems.getDefault().getPathMatcher("glob:" + e.value()));
                } catch (IllegalArgumentException bad) {
                    // invalid pattern: ignored for matching, still listed so the user can fix it
                }
            }
        }
        snapshot = new Snapshot(List.copyOf(ex), Set.copyOf(pins), List.copyOf(pol), List.copyOf(globs),
                Objects.hash(ex, pins, pol));
    }

    public ProtectedRules protectedRules() {
        return protectedRules;
    }

    record Snapshot(List<Exclusion> exclusions, Set<String> pins, List<PolicyRule> policies,
                    List<PathMatcher> globs, int hash) { }

    /** Kinds of restriction, in precedence order. */
    public enum Restriction { NONE, OUTSIDE_ROOT, REVOKED, PROTECTED, PINNED, EXCLUDED }

    public record Protection(Restriction restriction, String reason) {
        public boolean blocked() {
            return restriction != Restriction.NONE;
        }

        public static final Protection NONE = new Protection(Restriction.NONE, "");
    }

    /** Evaluates restrictions 1–3 for a path. {@code root} may be null when the path is in no root. */
    public Protection protection(Path path, boolean directory, long size, String category, String volumeId,
                                 MonitoredRoot root) {
        Optional<String> prot = protectedRules.protectionReason(path, directory);
        if (prot.isPresent()) return new Protection(Restriction.PROTECTED, prot.get());
        if (root == null || !PathUtil.isWithin(path, root.path())) {
            return new Protection(Restriction.OUTSIDE_ROOT, "Outside every authorized monitored folder");
        }
        if (!root.active()) {
            return new Protection(Restriction.REVOKED, "Authorization for " + root.path() + " has been revoked");
        }
        Snapshot s = snapshot;
        Path p = PathUtil.normalize(path);
        for (Path cur = p; cur != null; cur = cur.getParent()) {
            if (s.pins().contains(PathUtil.key(cur))) {
                return new Protection(Restriction.PINNED, cur.equals(p) ? "Pinned by you"
                        : "Inside pinned folder " + cur);
            }
        }
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        String ext = PathUtil.extension(name);
        for (Exclusion e : s.exclusions()) {
            boolean hit = switch (e.kind()) {
                case "PATH" -> PathUtil.isWithin(p, Path.of(e.value()));
                case "EXTENSION" -> !directory && ext.equals(e.value());
                case "MIN_SIZE" -> !directory && size < parseLong(e.value());
                case "MAX_SIZE" -> !directory && size > parseLong(e.value());
                case "CATEGORY" -> e.value().equalsIgnoreCase(category);
                case "VOLUME" -> e.value().equalsIgnoreCase(volumeId);
                default -> false;
            };
            if (hit) return new Protection(Restriction.EXCLUDED, "Excluded by rule " + e.kind() + " = " + e.value());
        }
        for (PathMatcher m : s.globs()) {
            if (m.matches(p) || (p.getFileName() != null && m.matches(p.getFileName()))) {
                return new Protection(Restriction.EXCLUDED, "Excluded by a glob pattern");
            }
        }
        return Protection.NONE;
    }

    private static long parseLong(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Effective (inherited) policy for a file.
     *
     * @param source human readable description of the most specific level that determined the threshold
     */
    public record EffectivePolicy(int inactivityDays, boolean neverOptimize, boolean archival,
                                  Confidence minConfidenceAuto, Set<Method> allowedMethods, String source,
                                  int version) { }

    public EffectivePolicy effective(Path path, String extension, MonitoredRoot root, Settings settings) {
        Snapshot s = snapshot;
        String fileKey = PathUtil.key(path);
        List<PolicyRule> chain = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        // FILE
        for (PolicyRule r : s.policies()) {
            if (r.scope() == PolicyRule.Scope.FILE && r.target().equals(fileKey)) {
                chain.add(r);
                labels.add("File policy");
            }
        }
        // DIRECTORY (deepest first)
        List<PolicyRule> dirs = new ArrayList<>();
        for (PolicyRule r : s.policies()) {
            if (r.scope() == PolicyRule.Scope.DIRECTORY && PathUtil.isWithin(path, Path.of(r.target()))
                    && !r.target().equals(fileKey)) {
                dirs.add(r);
            }
        }
        dirs.sort(Comparator.comparingInt((PolicyRule r) -> r.target().length()).reversed());
        for (PolicyRule r : dirs) {
            chain.add(r);
            labels.add("Folder policy for " + r.target());
        }
        // EXTENSION
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        for (PolicyRule r : s.policies()) {
            if (r.scope() == PolicyRule.Scope.EXTENSION && r.target().equals(ext)) {
                chain.add(r);
                labels.add("File type policy (." + ext + ")");
            }
        }
        // ROOT
        if (root != null) {
            for (PolicyRule r : s.policies()) {
                if (r.scope() == PolicyRule.Scope.ROOT && r.target().equals(Long.toString(root.id()))) {
                    chain.add(r);
                    labels.add("Monitored folder policy for " + root.path());
                }
            }
        }
        // GLOBAL
        for (PolicyRule r : s.policies()) {
            if (r.scope() == PolicyRule.Scope.GLOBAL) {
                chain.add(r);
                labels.add("Global policy");
            }
        }

        Integer days = null;
        String daysSource = null;
        Boolean never = null;
        Boolean archival = null;
        String minConf = null;
        String methods = null;
        for (int i = 0; i < chain.size(); i++) {
            PolicyRule r = chain.get(i);
            if (days == null && r.inactivityDays() != null) {
                days = r.inactivityDays();
                daysSource = labels.get(i);
            }
            if (never == null && r.neverOptimize() != null) never = r.neverOptimize();
            if (archival == null && r.archival() != null) archival = r.archival();
            if (minConf == null && r.minConfidence() != null) minConf = r.minConfidence();
            if (methods == null && r.allowedMethods() != null && !r.allowedMethods().isBlank()) methods = r.allowedMethods();
        }
        if (archival == null && root != null) archival = root.archival();
        if (days == null) {
            days = settings.inactivityDays();
            daysSource = "Default inactivity threshold";
        }
        Confidence conf = Confidence.parse(minConf, settings.minConfidenceAuto());
        if (!conf.atLeast(Confidence.MEDIUM)) conf = Confidence.MEDIUM;
        Set<Method> allowed = EnumSet.allOf(Method.class);
        if (methods != null) {
            allowed = EnumSet.noneOf(Method.class);
            for (String m : methods.split(",")) {
                try {
                    allowed.add(Method.valueOf(m.trim()));
                } catch (IllegalArgumentException ignored) {
                    // unknown method name in a stored policy: ignored (fails closed — fewer methods allowed)
                }
            }
        }
        String source = daysSource + " (" + days + " days)";
        return new EffectivePolicy(days, Boolean.TRUE.equals(never), Boolean.TRUE.equals(archival), conf,
                allowed, source, s.hash() * 31 + settingsHash(settings));
    }

    private static int settingsHash(Settings s) {
        return Objects.hash(s.inactivityDays(), s.observationDays(), s.minConfidenceAuto(), s.preferredMethod(),
                s.archiveReplaceEnabled(), s.minSavingsBytes(), s.minSavingsPercent(), s.minFileSize(), s.maxFileSize());
    }

    /** Version of the current rule set; stored with each operation for auditability. */
    public int version(Settings settings) {
        return snapshot.hash() * 31 + settingsHash(settings);
    }
}
