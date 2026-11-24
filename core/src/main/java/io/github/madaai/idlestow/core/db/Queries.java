package io.github.madaai.idlestow.core.db;

import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.util.PathUtil;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only queries for the explorer and dashboard. All are paginated and parameterized. */
public final class Queries {
    private final Database db;

    public Queries(Database db) {
        this.db = db;
    }

    public enum Filter {
        ALL("All files"), ACTIVE("Active"), INACTIVE_CANDIDATES("Inactive candidates"), UNKNOWN("Unknown activity"),
        OPTIMIZED("Optimized"), PINNED("Pinned"), EXCLUDED("Excluded"), FAILED("Failed"), ARCHIVED("Archived"),
        RECOVERY_REQUIRED("Recovery required");
        private final String label;
        Filter(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum SortField { NAME, TYPE, SIZE, PHYSICAL, POTENTIAL, ACTIVITY, STATUS }

    /** Row shown in the explorer: the item plus its classification, recommendation and directory aggregate. */
    public record Row(IndexedItem item, String classification, String confidence, String explanation, long idleDays,
                      long lastActivityMs, Long candidateSavings, String candidateMethod, Long dirFiles,
                      Long dirInactive, Long dirActive, Long dirEstReclaimable, Long dirActualReclaimed,
                      Long dirLogical, Long dirPhysical) { }

    private static final String BASE = "SELECT i.*, ac.classification, ac.confidence, ac.explanation,"
            + " COALESCE(ac.idle_days,-1) idle_days, COALESCE(ac.last_activity_ms,0) last_activity_ms,"
            + " oc.est_savings cand_savings, oc.method cand_method,"
            + " da.total_files, da.inactive_files, da.active_files, da.est_reclaimable, da.actual_reclaimed,"
            + " da.logical_bytes, da.physical_bytes"
            + " FROM indexed_items i"
            + " LEFT JOIN activity_classifications ac ON ac.item_id=i.id"
            + " LEFT JOIN optimization_candidates oc ON oc.item_id=i.id AND oc.status IN ('PENDING','APPROVED','POSTPONED')"
            + " LEFT JOIN directory_aggregates da ON da.path_key=i.path_key";

    private static Row map(java.sql.ResultSet rs) throws SQLException {
        return new Row(ItemRepository.map(rs), rs.getString("classification"), rs.getString("confidence"),
                rs.getString("explanation"), rs.getLong("idle_days"), rs.getLong("last_activity_ms"),
                Sql.getLong(rs, "cand_savings"), rs.getString("cand_method"), Sql.getLong(rs, "total_files"),
                Sql.getLong(rs, "inactive_files"), Sql.getLong(rs, "active_files"), Sql.getLong(rs, "est_reclaimable"),
                Sql.getLong(rs, "actual_reclaimed"), Sql.getLong(rs, "logical_bytes"), Sql.getLong(rs, "physical_bytes"));
    }

    /**
     * Lists entries. With {@code parent} set and no search text, lists direct children (folders first);
     * with search text, searches the whole subtree of {@code parent} (or everything when null).
     */
    public List<Row> list(Path parent, String search, Filter filter, SortField sort, boolean ascending, int offset,
                          int limit) throws SQLException {
        StringBuilder sql = new StringBuilder(BASE).append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        boolean searching = search != null && !search.isBlank();
        if (parent != null) {
            String key = PathUtil.key(parent);
            if (searching || (filter != null && filter != Filter.ALL)) {
                String sep = PathUtil.isWindows() ? "\\" : "/";
                sql.append(" AND i.path_key LIKE ? ESCAPE '\\'");
                args.add(Sql.likeEscape(key.endsWith(sep) ? key : key + sep) + "%");
            } else {
                sql.append(" AND i.parent_key=?");
                args.add(key);
            }
        }
        if (searching) {
            sql.append(" AND i.name_lc LIKE ? ESCAPE '\\'");
            args.add("%" + Sql.likeEscape(search.trim().toLowerCase(Locale.ROOT)) + "%");
        }
        if (filter != null) {
            switch (filter) {
                case ALL -> { }
                case ACTIVE -> sql.append(" AND i.is_dir=0 AND ac.classification IN ('ACTIVE','PROBABLY_ACTIVE')");
                case INACTIVE_CANDIDATES -> sql.append(" AND i.is_dir=0 AND oc.id IS NOT NULL");
                case UNKNOWN -> sql.append(" AND i.is_dir=0 AND ac.classification='UNKNOWN'");
                case OPTIMIZED -> sql.append(" AND i.opt_status IN ('NATIVE_COMPRESSED','ARCHIVE_COPY','ARCHIVED')");
                case PINNED -> sql.append(" AND ac.classification='PINNED'");
                case EXCLUDED -> sql.append(" AND ac.classification='EXCLUDED'");
                case FAILED -> sql.append(" AND i.opt_status='FAILED'");
                case ARCHIVED -> sql.append(" AND i.opt_status='ARCHIVED'");
                case RECOVERY_REQUIRED -> sql.append(" AND i.opt_status='RECOVERY_REQUIRED'");
            }
        }
        if (filter == null || filter == Filter.ALL || filter == Filter.ARCHIVED) {
            // archived originals are listed (not present) so they can be restored
        } else {
            sql.append(" AND (i.present=1 OR i.opt_status='ARCHIVED')");
        }
        String dir = ascending ? " ASC" : " DESC";
        String order = switch (sort == null ? SortField.NAME : sort) {
            case NAME -> "i.name_lc" + dir;
            case TYPE -> "i.category" + dir + ", i.name_lc";
            case SIZE -> "i.logical_size" + dir;
            case PHYSICAL -> "i.physical_size" + dir;
            case POTENTIAL -> "COALESCE(oc.est_savings, da.est_reclaimable, 0)" + dir;
            case ACTIVITY -> "idle_days" + dir;
            case STATUS -> "i.opt_status" + dir;
        };
        sql.append(" ORDER BY i.is_dir DESC, ").append(order).append(" LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return db.read(c -> Sql.list(c, sql.toString(), Queries::map, args.toArray()));
    }

    public java.util.Optional<Row> row(long itemId) throws SQLException {
        return db.read(c -> Sql.one(c, BASE + " WHERE i.id=?", Queries::map, itemId));
    }

    // ---------------------------------------------------------------- dashboard

    public record Totals(long files, long dirs, long logical, long physical, long roots, long optimizedFiles,
                         long archivedFiles, long recoveryRequired) { }

    public Totals totals() throws SQLException {
        return db.read(c -> {
            long files = Sql.scalarLong(c, "SELECT COUNT(*) FROM indexed_items WHERE is_dir=0 AND present=1");
            long dirs = Sql.scalarLong(c, "SELECT COUNT(*) FROM indexed_items WHERE is_dir=1 AND present=1");
            // Hard-linked files are counted once per (volume, file id).
            long logical = Sql.scalarLong(c, "SELECT COALESCE(SUM(s),0) FROM (SELECT MAX(logical_size) s FROM"
                    + " indexed_items WHERE is_dir=0 AND present=1 GROUP BY COALESCE(file_key, 'p'||id))");
            long physical = Sql.scalarLong(c, "SELECT COALESCE(SUM(s),0) FROM (SELECT MAX(CASE WHEN physical_size>=0"
                    + " THEN physical_size ELSE logical_size END) s FROM indexed_items WHERE is_dir=0 AND present=1"
                    + " GROUP BY COALESCE(file_key, 'p'||id))");
            long roots = Sql.scalarLong(c, "SELECT COUNT(*) FROM monitored_roots WHERE revoked_utc IS NULL");
            long opt = Sql.scalarLong(c, "SELECT COUNT(*) FROM indexed_items WHERE opt_status='NATIVE_COMPRESSED'");
            long arch = Sql.scalarLong(c, "SELECT COUNT(*) FROM indexed_items WHERE opt_status='ARCHIVED'");
            long rec = Sql.scalarLong(c, "SELECT COUNT(*) FROM indexed_items WHERE opt_status='RECOVERY_REQUIRED'");
            return new Totals(files, dirs, logical, physical, roots, opt, arch, rec);
        });
    }

    /**
     * Space currently reclaimed, measured from live data (never from cumulative history):
     * natively compressed files: cluster-rounded logical size − measured allocation;
     * archived originals whose retained copy has been purged: original size − archive size.
     */
    public long currentReclaimed() throws SQLException {
        return db.read(c -> {
            // grouped by file identity so hard-linked paths are counted once
            long nat = Sql.scalarLong(c, "SELECT COALESCE(SUM(s),0) FROM (SELECT MAX(MAX(0, ((i.logical_size"
                    + " + COALESCE(v.cluster_size,4096) - 1) / COALESCE(v.cluster_size,4096)) * COALESCE(v.cluster_size,4096)"
                    + " - i.physical_size)) s FROM indexed_items i LEFT JOIN filesystem_volumes v ON v.volume_id=i.volume_id"
                    + " WHERE i.opt_status='NATIVE_COMPRESSED' AND i.present=1 AND i.physical_exact=1 AND i.physical_size>=0"
                    + " GROUP BY COALESCE(i.file_key, 'p'||i.id))");
            long arch = Sql.scalarLong(c, "SELECT COALESCE(SUM(MAX(0, size - COALESCE(archive_size,0))),0)"
                    + " FROM recovery_records WHERE kind='ARCHIVE_REPLACE' AND status='ORIGINAL_PURGED'");
            return nat + arch;
        });
    }

    /** Bytes saved once retained originals are purged (archive replacements still in retention). */
    public long pendingAfterRetention() throws SQLException {
        return db.read(c -> Sql.scalarLong(c, "SELECT COALESCE(SUM(MAX(0, size - COALESCE(archive_size,0))),0)"
                + " FROM recovery_records WHERE kind='ARCHIVE_REPLACE' AND status='ACTIVE'"));
    }

    /** Category → [files, logical bytes]. */
    public List<Object[]> byCategory() throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT category, COUNT(*), COALESCE(SUM(logical_size),0) FROM indexed_items"
                        + " WHERE is_dir=0 AND present=1 GROUP BY category ORDER BY 3 DESC",
                rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3)}));
    }

    /** Root path → [files, logical bytes]. */
    public List<Object[]> byRoot() throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT r.display_name, COUNT(i.id), COALESCE(SUM(i.logical_size),0)"
                        + " FROM monitored_roots r LEFT JOIN indexed_items i ON i.root_id=r.id AND i.is_dir=0 AND i.present=1"
                        + " WHERE r.revoked_utc IS NULL GROUP BY r.id ORDER BY 3 DESC",
                rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3)}));
    }

    /** Classification → [files, bytes]. */
    public List<Object[]> byActivity() throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT ac.classification, COUNT(*), COALESCE(SUM(i.logical_size),0)"
                        + " FROM activity_classifications ac JOIN indexed_items i ON i.id=ac.item_id"
                        + " WHERE i.present=1 GROUP BY ac.classification ORDER BY 2 DESC",
                rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3)}));
    }

    /** Largest pending candidates: [path, est savings, method]. */
    public List<Object[]> largestCandidates(int limit) throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT i.path, oc.est_savings, oc.method FROM optimization_candidates oc"
                        + " JOIN indexed_items i ON i.id=oc.item_id WHERE oc.status='PENDING' ORDER BY oc.est_savings DESC"
                        + " LIMIT ?", rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getString(3)}, limit));
    }

    /** Compression effectiveness: [method, operations, logical bytes, before physical, after physical]. */
    public List<Object[]> effectiveness() throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT method, COUNT(*), COALESCE(SUM(logical_size),0),"
                        + " COALESCE(SUM(before_physical),0), COALESCE(SUM(after_physical),0) FROM optimization_history"
                        + " WHERE action='OPTIMIZE' AND outcome='SUCCEEDED' GROUP BY method",
                rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)}));
    }
}
