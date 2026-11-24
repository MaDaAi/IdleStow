package io.github.madaai.idlestow.core.db;

import io.github.madaai.idlestow.core.policy.PolicyRule;
import io.github.madaai.idlestow.core.util.PathUtil;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

/** Persistence for user exclusions, pins and scoped policies. */
public final class RulesRepository {
    private final Database db;
    private final Clock clock;

    public RulesRepository(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public record Exclusion(long id, String kind, String value) { }

    public record Pin(String pathKey, Path path) { }

    public static final List<String> EXCLUSION_KINDS =
            List.of("PATH", "GLOB", "EXTENSION", "MIN_SIZE", "MAX_SIZE", "CATEGORY", "VOLUME");

    public List<Exclusion> exclusions() throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT id, kind, value FROM exclusions ORDER BY kind, value",
                rs -> new Exclusion(rs.getLong(1), rs.getString(2), rs.getString(3))));
    }

    public void addExclusion(String kind, String value) throws SQLException {
        if (!EXCLUSION_KINDS.contains(kind)) throw new IllegalArgumentException("Unknown exclusion kind " + kind);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Exclusion value is empty");
        String v = switch (kind) {
            case "PATH" -> PathUtil.normalize(Path.of(value.trim())).toString();
            case "EXTENSION" -> value.trim().toLowerCase(java.util.Locale.ROOT).replaceFirst("^\\.", "");
            case "MIN_SIZE", "MAX_SIZE" -> Long.toString(Long.parseLong(value.trim()));
            case "GLOB" -> {
                java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + value.trim()); // throws if invalid
                yield value.trim();
            }
            default -> value.trim();
        };
        db.tx(c -> Sql.update(c, "INSERT OR IGNORE INTO exclusions(kind, value, created_utc) VALUES(?,?,?)",
                kind, v, clock.instant().toString()));
    }

    public void removeExclusion(long id) throws SQLException {
        db.tx(c -> Sql.update(c, "DELETE FROM exclusions WHERE id=?", id));
    }

    public List<Pin> pins() throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT path_key, path FROM pinned_items ORDER BY path_key",
                rs -> new Pin(rs.getString(1), Path.of(rs.getString(2)))));
    }

    public void pin(Path p) throws SQLException {
        db.tx(c -> Sql.update(c, "INSERT OR IGNORE INTO pinned_items(path_key, path, created_utc) VALUES(?,?,?)",
                PathUtil.key(p), PathUtil.normalize(p).toString(), clock.instant().toString()));
    }

    public void unpin(Path p) throws SQLException {
        db.tx(c -> Sql.update(c, "DELETE FROM pinned_items WHERE path_key=?", PathUtil.key(p)));
    }

    public List<PolicyRule> policies() throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT * FROM policies ORDER BY scope, target", rs -> new PolicyRule(
                rs.getLong("id"), PolicyRule.Scope.valueOf(rs.getString("scope")), rs.getString("target"),
                (Integer) rs.getObject("inactivity_days"), toBool(rs.getObject("never_optimize")),
                toBool(rs.getObject("archival")), rs.getString("min_confidence"), rs.getString("allowed_methods"))));
    }

    private static Boolean toBool(Object o) {
        if (o == null) return null;
        return ((Number) o).intValue() != 0;
    }

    /** Inserts or replaces the policy for (scope, target). */
    public void savePolicy(PolicyRule r) throws SQLException {
        String target = r.scope() == PolicyRule.Scope.DIRECTORY || r.scope() == PolicyRule.Scope.FILE
                ? PathUtil.key(Path.of(r.target())) : r.target();
        db.tx(c -> Sql.update(c, "INSERT INTO policies(scope, target, inactivity_days, never_optimize, archival,"
                        + " min_confidence, allowed_methods, updated_utc) VALUES(?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT(scope, target) DO UPDATE SET inactivity_days=excluded.inactivity_days,"
                        + " never_optimize=excluded.never_optimize, archival=excluded.archival,"
                        + " min_confidence=excluded.min_confidence, allowed_methods=excluded.allowed_methods,"
                        + " updated_utc=excluded.updated_utc",
                r.scope(), target, r.inactivityDays(), r.neverOptimize(), r.archival(), r.minConfidence(),
                r.allowedMethods(), clock.instant().toString()));
    }

    public void deletePolicy(long id) throws SQLException {
        db.tx(c -> Sql.update(c, "DELETE FROM policies WHERE id=?", id));
    }
}
