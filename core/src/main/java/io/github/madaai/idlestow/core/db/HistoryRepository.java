package io.github.madaai.idlestow.core.db;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** User-facing optimization history (separate from diagnostic logs; never contains file contents). */
public final class HistoryRepository {
    private final Database db;
    private final Clock clock;

    public HistoryRepository(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public record Entry(long id, Long operationId, String atUtc, String path, String fileKey, String action,
                        String method, String origin, String authorizationMode, String policy, Long logicalSize,
                        Long beforePhysical, Long afterPhysical, Long estSavings, Long actualSavings,
                        String verification, String recovery, String outcome, String error) { }

    public long add(Entry e) throws SQLException {
        return db.txResult(c -> Sql.insert(c, "INSERT INTO optimization_history(operation_id, at_utc, path, file_key,"
                        + " action, method, origin, authorization_mode, policy, logical_size, before_physical,"
                        + " after_physical, est_savings, actual_savings, verification, recovery, outcome, error)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                e.operationId(), e.atUtc() != null ? e.atUtc() : clock.instant().toString(), e.path(), e.fileKey(),
                e.action(), e.method(), e.origin(), e.authorizationMode(), e.policy(), e.logicalSize(),
                e.beforePhysical(), e.afterPhysical(), e.estSavings(), e.actualSavings(), e.verification(),
                e.recovery(), e.outcome(), e.error()));
    }

    /** Searches history (path substring, outcome filter), newest first unless {@code ascending}. */
    public List<Entry> search(String text, String outcome, boolean ascending, int offset, int limit)
            throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM optimization_history WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            sql.append(" AND path LIKE ? ESCAPE '\\'");
            args.add("%" + Sql.likeEscape(text.trim()) + "%");
        }
        if (outcome != null && !outcome.isBlank()) {
            sql.append(" AND outcome=?");
            args.add(outcome);
        }
        sql.append(" ORDER BY at_utc ").append(ascending ? "ASC" : "DESC").append(", id ")
                .append(ascending ? "ASC" : "DESC").append(" LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return db.read(c -> Sql.list(c, sql.toString(), rs -> new Entry(rs.getLong("id"),
                Sql.getLong(rs, "operation_id"), rs.getString("at_utc"), rs.getString("path"), rs.getString("file_key"),
                rs.getString("action"), rs.getString("method"), rs.getString("origin"),
                rs.getString("authorization_mode"), rs.getString("policy"), Sql.getLong(rs, "logical_size"),
                Sql.getLong(rs, "before_physical"), Sql.getLong(rs, "after_physical"), Sql.getLong(rs, "est_savings"),
                Sql.getLong(rs, "actual_savings"), rs.getString("verification"), rs.getString("recovery"),
                rs.getString("outcome"), rs.getString("error")), args.toArray()));
    }

    /** Daily sum of measured savings for successful optimizations (for the savings-over-time chart). */
    public List<long[]> savingsPerDay(int days) throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT CAST(julianday(substr(at_utc,1,10)) AS INTEGER) d,"
                        + " SUM(CASE WHEN outcome='SUCCEEDED' THEN COALESCE(actual_savings,0)"
                        + " WHEN outcome='RESTORED' THEN -COALESCE(actual_savings,0) ELSE 0 END)"
                        + " FROM optimization_history WHERE at_utc >= date('now', ?) GROUP BY d ORDER BY d",
                rs -> new long[]{rs.getLong(1), rs.getLong(2)}, "-" + days + " days"));
    }

    public long count() throws SQLException {
        return db.read(c -> Sql.scalarLong(c, "SELECT COUNT(*) FROM optimization_history"));
    }
}
