package io.github.madaai.idlestow.core.db;

import io.github.madaai.idlestow.core.model.Enums.CandidateStatus;
import io.github.madaai.idlestow.core.model.Enums.Method;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Recommendations (optimization candidates), optimization plans and plan-bound approvals. */
public final class CandidateRepository {
    private final Database db;
    private final Clock clock;

    public CandidateRepository(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public record Candidate(long id, long itemId, long rootId, String path, String name, String extension,
                            long logicalSize, Method method, long estSavings, String estBasis, String reason,
                            String policyRef, String classification, String confidence, String warnings,
                            boolean autoEligible, CandidateStatus status, Long postponedUntilMs, long snapshotSize,
                            long snapshotMtime, String snapshotKey, long idleDays) { }

    public record NewCandidate(long itemId, Method method, long estSavings, String estBasis, String reason,
                               String policyRef, String classification, String confidence, String warnings,
                               boolean autoEligible, long snapshotSize, long snapshotMtime, String snapshotKey) { }

    /**
     * Replaces the recommendation set for the given root: upserts current candidates (keeping user decisions
     * such as REJECTED/DISMISSED/POSTPONED while the file is unchanged) and removes PENDING candidates
     * that are no longer eligible.
     */
    public void replaceForRoot(long rootId, List<NewCandidate> fresh) throws SQLException {
        String now = clock.instant().toString();
        db.tx(c -> {
            Sql.update(c, "CREATE TEMP TABLE IF NOT EXISTS keep_items(item_id INTEGER PRIMARY KEY)");
            Sql.update(c, "DELETE FROM keep_items");
            for (NewCandidate n : fresh) {
                Sql.update(c, "INSERT OR IGNORE INTO keep_items(item_id) VALUES(?)", n.itemId());
                Sql.update(c, "INSERT INTO optimization_candidates(item_id, method, est_savings, est_basis, reason,"
                                + " policy_ref, classification, confidence, warnings, auto_eligible, status,"
                                + " snapshot_size, snapshot_mtime, snapshot_key, created_utc, updated_utc)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?) ON CONFLICT(item_id) DO UPDATE SET"
                                + " method=excluded.method, est_savings=excluded.est_savings, est_basis=excluded.est_basis,"
                                + " reason=excluded.reason, policy_ref=excluded.policy_ref,"
                                + " classification=excluded.classification, confidence=excluded.confidence,"
                                + " warnings=excluded.warnings, auto_eligible=excluded.auto_eligible,"
                                // a user decision only survives while the file is unchanged
                                + " status=CASE WHEN optimization_candidates.snapshot_size=excluded.snapshot_size"
                                + "   AND optimization_candidates.snapshot_mtime=excluded.snapshot_mtime"
                                + "   AND optimization_candidates.status IN ('REJECTED','DISMISSED','POSTPONED','APPROVED')"
                                + "   THEN optimization_candidates.status ELSE 'PENDING' END,"
                                + " snapshot_size=excluded.snapshot_size, snapshot_mtime=excluded.snapshot_mtime,"
                                + " snapshot_key=excluded.snapshot_key, updated_utc=excluded.updated_utc",
                        n.itemId(), n.method(), n.estSavings(), n.estBasis(), n.reason(), n.policyRef(),
                        n.classification(), n.confidence(), n.warnings(), n.autoEligible(), n.snapshotSize(),
                        n.snapshotMtime(), n.snapshotKey(), now, now);
            }
            Sql.update(c, "DELETE FROM optimization_candidates WHERE status IN ('PENDING','APPROVED') AND item_id IN"
                    + " (SELECT id FROM indexed_items WHERE root_id=?) AND item_id NOT IN (SELECT item_id FROM keep_items)",
                    rootId);
            // un-postpone expired postponements
            Sql.update(c, "UPDATE optimization_candidates SET status='PENDING' WHERE status='POSTPONED'"
                    + " AND postponed_until_ms IS NOT NULL AND postponed_until_ms < ?", clock.millis());
        });
    }

    private static final String SELECT = "SELECT oc.*, i.path, i.name, i.extension, i.root_id, i.logical_size,"
            + " COALESCE(ac.idle_days,-1) idle_days FROM optimization_candidates oc"
            + " JOIN indexed_items i ON i.id=oc.item_id"
            + " LEFT JOIN activity_classifications ac ON ac.item_id=oc.item_id";

    private static Candidate map(java.sql.ResultSet rs) throws SQLException {
        return new Candidate(rs.getLong("id"), rs.getLong("item_id"), rs.getLong("root_id"), rs.getString("path"),
                rs.getString("name"), rs.getString("extension"), rs.getLong("logical_size"),
                Method.valueOf(rs.getString("method")), rs.getLong("est_savings"), rs.getString("est_basis"),
                rs.getString("reason"), rs.getString("policy_ref"), rs.getString("classification"),
                rs.getString("confidence"), rs.getString("warnings"), rs.getInt("auto_eligible") == 1,
                CandidateStatus.valueOf(rs.getString("status")), Sql.getLong(rs, "postponed_until_ms"),
                rs.getLong("snapshot_size"), rs.getLong("snapshot_mtime"), rs.getString("snapshot_key"),
                rs.getLong("idle_days"));
    }

    public enum Sort { SAVINGS, IDLE, CONFIDENCE, PATH }

    public List<Candidate> list(List<CandidateStatus> statuses, Long rootId, String extension, Sort sort, int limit)
            throws SQLException {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND oc.status IN (");
            for (int i = 0; i < statuses.size(); i++) {
                sql.append(i == 0 ? "?" : ",?");
                args.add(statuses.get(i));
            }
            sql.append(")");
        }
        if (rootId != null) {
            sql.append(" AND i.root_id=?");
            args.add(rootId);
        }
        if (extension != null && !extension.isBlank()) {
            sql.append(" AND i.extension=?");
            args.add(extension.toLowerCase(java.util.Locale.ROOT));
        }
        sql.append(switch (sort == null ? Sort.SAVINGS : sort) {
            case SAVINGS -> " ORDER BY oc.est_savings DESC";
            case IDLE -> " ORDER BY idle_days DESC";
            case CONFIDENCE -> " ORDER BY CASE oc.confidence WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, oc.est_savings DESC";
            case PATH -> " ORDER BY i.path_key";
        });
        sql.append(" LIMIT ?");
        args.add(limit);
        return db.read(c -> Sql.list(c, sql.toString(), CandidateRepository::map, args.toArray()));
    }

    public Optional<Candidate> byItem(long itemId) throws SQLException {
        return db.read(c -> Sql.one(c, SELECT + " WHERE oc.item_id=?", CandidateRepository::map, itemId));
    }

    public void setStatus(List<Long> candidateIds, CandidateStatus status, Long postponedUntilMs) throws SQLException {
        String now = clock.instant().toString();
        db.tx(c -> {
            for (Long id : candidateIds) {
                Sql.update(c, "UPDATE optimization_candidates SET status=?, postponed_until_ms=?, updated_utc=? WHERE id=?",
                        status, postponedUntilMs, now, id);
            }
        });
    }

    public void markDoneForItem(long itemId) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE optimization_candidates SET status='DONE', updated_utc=? WHERE item_id=?",
                clock.instant().toString(), itemId));
    }

    public long pendingCount() throws SQLException {
        return db.read(c -> Sql.scalarLong(c, "SELECT COUNT(*) FROM optimization_candidates WHERE status='PENDING'"));
    }

    public long pendingSavings() throws SQLException {
        return db.read(c -> Sql.scalarLong(c,
                "SELECT COALESCE(SUM(est_savings),0) FROM optimization_candidates WHERE status IN ('PENDING','APPROVED')"));
    }

    // ------------------------------------------------------------------ plans & approvals

    public record PlanItem(long itemId, String path, long snapshotSize, long snapshotMtime, String snapshotKey) { }

    public record Plan(long id, Method method, String origin, String authorizationMode, int policyVersion,
                       String status, int itemCount, long estSavings, String createdUtc) { }

    public long createPlan(Method method, String origin, String mode, int policyVersion, List<PlanItem> items,
                           long estSavings) throws SQLException {
        String now = clock.instant().toString();
        return db.txResult(c -> {
            long id = Sql.insert(c, "INSERT INTO optimization_plans(method, origin, authorization_mode, policy_version,"
                            + " status, item_count, est_savings, created_utc) VALUES(?,?,?,?,'DRAFT',?,?,?)",
                    method, origin, mode, policyVersion, items.size(), estSavings, now);
            for (PlanItem it : items) {
                Sql.update(c, "INSERT INTO plan_items(plan_id, item_id, path, snapshot_size, snapshot_mtime, snapshot_key)"
                                + " VALUES(?,?,?,?,?,?)", id, it.itemId(), it.path(), it.snapshotSize(), it.snapshotMtime(),
                        it.snapshotKey());
            }
            return id;
        });
    }

    public List<PlanItem> planItems(long planId) throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT * FROM plan_items WHERE plan_id=? ORDER BY item_id",
                rs -> new PlanItem(rs.getLong("item_id"), rs.getString("path"), rs.getLong("snapshot_size"),
                        rs.getLong("snapshot_mtime"), rs.getString("snapshot_key")), planId));
    }

    public Optional<Plan> plan(long planId) throws SQLException {
        return db.read(c -> Sql.one(c, "SELECT * FROM optimization_plans WHERE id=?", rs -> new Plan(rs.getLong("id"),
                Method.valueOf(rs.getString("method")), rs.getString("origin"), rs.getString("authorization_mode"),
                rs.getInt("policy_version"), rs.getString("status"), rs.getInt("item_count"),
                rs.getLong("est_savings"), rs.getString("created_utc")), planId));
    }

    /** Digest binding an approval to the exact plan contents (paths, sizes, mtimes, identities, method). */
    public static String digest(Method method, List<PlanItem> items) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(method.name().getBytes(StandardCharsets.UTF_8));
            for (PlanItem i : items) {
                md.update(("\n" + i.itemId() + "|" + i.path() + "|" + i.snapshotSize() + "|" + i.snapshotMtime() + "|"
                        + i.snapshotKey()).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records an approval for a plan. Approvals expire (default 24 h) and are single-use. */
    public void approve(long planId, Duration validity) throws SQLException {
        Plan p = plan(planId).orElseThrow(() -> new SQLException("No plan " + planId));
        String d = digest(p.method(), planItems(planId));
        String now = clock.instant().toString();
        String exp = clock.instant().plus(validity).toString();
        db.tx(c -> {
            // A plan is approved at most once; an approval can never be re-armed after it was used.
            int n = Sql.update(c, "INSERT OR IGNORE INTO approvals(plan_id, approved_utc, plan_digest, expires_utc)"
                    + " VALUES(?,?,?,?)", planId, now, d, exp);
            if (n == 0) throw new SQLException("Plan " + planId + " was already approved");
            Sql.update(c, "UPDATE optimization_plans SET status='APPROVED' WHERE id=?", planId);
        });
    }

    /**
     * Validates and consumes the approval for a plan. Returns an empty string when valid,
     * otherwise the reason it is not valid. A consumed approval can never be reused.
     */
    public String consumeApproval(long planId) throws SQLException {
        Plan p = plan(planId).orElse(null);
        if (p == null) return "Plan not found";
        String expected = digest(p.method(), planItems(planId));
        String now = clock.instant().toString();
        return db.txResult(c -> {
            Optional<String[]> a = Sql.one(c, "SELECT plan_digest, expires_utc, consumed_utc FROM approvals WHERE plan_id=?",
                    rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, planId);
            if (a.isEmpty()) return "The plan has not been approved";
            if (a.get()[2] != null) return "The approval was already used";
            if (a.get()[1].compareTo(now) < 0) return "The approval has expired";
            if (!a.get()[0].equals(expected)) return "The plan changed after it was approved";
            Sql.update(c, "UPDATE approvals SET consumed_utc=? WHERE plan_id=?", now, planId);
            Sql.update(c, "UPDATE optimization_plans SET status='EXECUTED' WHERE id=?", planId);
            return "";
        });
    }
}
