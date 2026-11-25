package io.github.madaai.idlestow.core.db;

import io.github.madaai.idlestow.core.index.ScannedEntry;
import io.github.madaai.idlestow.core.model.ActivityAssessment;
import io.github.madaai.idlestow.core.model.Enums.ActivityClass;
import io.github.madaai.idlestow.core.model.Enums.Confidence;
import io.github.madaai.idlestow.core.model.Enums.OptStatus;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.util.PathUtil;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Persistence for the file index, activity evidence and classifications. */
public final class ItemRepository {

    /**
     * Slack around a recorded IdleStow read in which a last-access time is attributed to IdleStow itself.
     * Last-access values carry the time of the access, so only the read interval itself (plus clock/filesystem
     * granularity) is masked; any later access is treated as user activity.
     */
    static final long SELF_READ_BEFORE_MS = 5_000L;
    static final long SELF_READ_AFTER_MS = 5_000L;

    private final Database db;
    private final Clock clock;

    public ItemRepository(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- mapping

    static IndexedItem map(ResultSet rs) throws SQLException {
        String flags = rs.getString("attr_flags");
        Set<String> attrs = flags == null || flags.isBlank() ? Set.of()
                : new LinkedHashSet<>(Arrays.asList(flags.split(",")));
        Long forSize = Sql.getLong(rs, "est_for_size");
        IndexedItem.Estimate est = forSize == null ? null : new IndexedItem.Estimate(
                Sql.getDouble(rs, "est_zip_ratio"), Sql.getLong(rs, "est_ntfs_savings"),
                Sql.getLong(rs, "est_zip_savings"), Sql.getLong(rs, "est_zstd_savings"),
                rs.getString("est_format"), rs.getString("est_basis"), forSize,
                Sql.getLong(rs, "est_for_modified_ms", 0));
        return new IndexedItem(rs.getLong("id"), rs.getLong("root_id"), Path.of(rs.getString("path")),
                rs.getString("rel_path"), rs.getString("name"), rs.getString("extension"), rs.getString("category"),
                rs.getInt("is_dir") == 1, rs.getInt("present") == 1, rs.getInt("special") == 1,
                rs.getString("file_key"), rs.getString("volume_id"), rs.getLong("logical_size"),
                rs.getLong("physical_size"), rs.getInt("physical_exact") == 1,
                Sql.getLong(rs, "created_ms", 0), Sql.getLong(rs, "modified_ms", 0),
                Sql.getLong(rs, "accessed_ms", 0), Sql.getLong(rs, "trusted_access_ms", 0),
                rs.getString("first_indexed_utc"), rs.getString("last_scanned_utc"),
                rs.getInt("link_count"), rs.getInt("alt_streams"), attrs, rs.getInt("native_compressed") == 1,
                OptStatus.valueOf(rs.getString("opt_status")), est, rs.getString("error"));
    }

    // ---------------------------------------------------------------- scan upserts

    /** Outcome counters of a batch upsert. */
    public record UpsertStats(int inserted, int updated, int moved, int changed) { }

    /** The existing row for a path, with the columns that decide whether a scan found anything different. */
    private static final String LOOKUP_SQL = "SELECT id, COALESCE(modified_ms,0), logical_size, COALESCE(accessed_ms,0),"
            + " COALESCE(trusted_access_ms,0), root_id, path, parent_key, rel_path, name, extension, category, is_dir,"
            + " special, file_key, volume_id, physical_size, physical_exact, created_ms, link_count, alt_streams,"
            + " attr_flags, native_compressed, opt_status, present FROM indexed_items WHERE path_key=?";

    private static final String MOVE_SQL = "SELECT id, path FROM indexed_items WHERE file_key=? AND root_id=? "
            + "AND scan_generation<? AND present=1 AND is_dir=0";

    private static final String INSERT_SQL = "INSERT INTO indexed_items(root_id, path, path_key, parent_key, rel_path,"
            + " name, name_lc, extension, category, is_dir, present, special, file_key, volume_id, logical_size,"
            + " physical_size, physical_exact, created_ms, modified_ms, accessed_ms, trusted_access_ms,"
            + " first_indexed_utc, last_scanned_utc, link_count, alt_streams, attr_flags, native_compressed, opt_status,"
            + " scan_generation) VALUES(?,?,?,?,?,?,?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String UPDATE_SQL = "UPDATE indexed_items SET root_id=?, path=?, path_key=?, parent_key=?,"
            + " rel_path=?, name=?, name_lc=?, extension=?, category=?, is_dir=?, present=1, special=?, file_key=?,"
            + " volume_id=?, logical_size=?, physical_size=?, physical_exact=?, created_ms=?, modified_ms=?,"
            + " accessed_ms=?, trusted_access_ms=?, last_scanned_utc=?, link_count=?, alt_streams=?, attr_flags=?,"
            + " native_compressed=?,"
            + " last_changed_utc=CASE WHEN ? THEN ? ELSE last_changed_utc END,"
            + " opt_status=CASE"
            + "   WHEN opt_status IN ('NONE','EXTERNALLY_COMPRESSED') AND ?=1 THEN 'EXTERNALLY_COMPRESSED'"
            + "   WHEN opt_status='EXTERNALLY_COMPRESSED' AND ?=0 THEN 'NONE'"
            + "   WHEN opt_status='NATIVE_COMPRESSED' AND ?=0 THEN 'NONE'"
            + "   WHEN opt_status='NO_BENEFIT' AND ? THEN 'NONE'"
            + "   ELSE opt_status END,"
            + " scan_generation=? WHERE id=?";

    /**
     * Cheaper update for a row that a rescan found completely unchanged: only the bookkeeping columns
     * (scan generation, scan time) are written, so the other indexes of the row are left alone.
     */
    private static final String TOUCH_SQL = "UPDATE indexed_items SET last_scanned_utc=?, scan_generation=? WHERE id=?";

    /**
     * Inserts or updates a batch of scanned entries in one transaction.
     * Detects modifications (recording evidence) and moves/renames via stable file keys.
     * Statements are prepared once per batch rather than once per row.
     */
    public UpsertStats upsertBatch(long rootId, long generation, List<ScannedEntry> batch) throws SQLException {
        String now = clock.instant().toString();
        return db.txResult(c -> {
            int ins = 0, upd = 0, mov = 0, chg = 0;
            try (PreparedStatement lookup = c.prepareStatement(LOOKUP_SQL);
                 PreparedStatement move = c.prepareStatement(MOVE_SQL);
                 PreparedStatement insert = c.prepareStatement(INSERT_SQL);
                 PreparedStatement update = c.prepareStatement(UPDATE_SQL);
                 PreparedStatement touch = c.prepareStatement(TOUCH_SQL)) {
                for (ScannedEntry e : batch) {
                    String pathStr = e.path().toString();
                    String key = PathUtil.key(e.path());
                    String nameLc = e.name().toLowerCase(java.util.Locale.ROOT);
                    String flags = flags(e.attrs());
                    lookup.setString(1, key);
                    try (ResultSet rs = lookup.executeQuery()) {
                        if (rs.next()) {
                            long id = rs.getLong(1);
                            boolean changed = !e.directory()
                                    && (rs.getLong(2) != e.modifiedMs() || rs.getLong(3) != e.logicalSize());
                            long trusted = trustedAccess(c, id, rs.getLong(4), rs.getLong(5), e.accessedMs());
                            boolean same = !changed && trusted == rs.getLong(5)
                                    && unchanged(rs, rootId, pathStr, flags, e);
                            rs.close(); // finish the read before writing the same row
                            if (same) {
                                Sql.bind(touch, now, generation, id);
                                touch.executeUpdate();
                            } else {
                                Sql.bind(update, rootId, pathStr, key, e.parentKey(), e.relPath(), e.name(), nameLc,
                                        e.extension(), e.category(), e.directory(), e.special(), e.fileKey(),
                                        e.volumeId(), e.logicalSize(), e.physicalSize(), e.physicalExact(),
                                        e.createdMs(), e.modifiedMs(), e.accessedMs(), trusted, now, e.linkCount(),
                                        e.altStreams(), flags, e.nativeCompressed(), changed, now,
                                        e.nativeCompressed() ? 1 : 0, e.nativeCompressed() ? 1 : 0,
                                        e.nativeCompressed() ? 1 : 0, changed, generation, id);
                                update.executeUpdate();
                            }
                            if (changed) {
                                chg++;
                                addEvidence(c, id, "MODIFIED_OBSERVED", e.modifiedMs(), null,
                                        "Content or size changed since the previous scan", now);
                            }
                            upd++;
                            continue;
                        }
                    }
                    Long movedFrom = findMoveSource(move, rootId, generation, e);
                    if (movedFrom != null) {
                        Sql.bind(update, rootId, pathStr, key, e.parentKey(), e.relPath(), e.name(), nameLc,
                                e.extension(), e.category(), e.directory(), e.special(), e.fileKey(), e.volumeId(),
                                e.logicalSize(), e.physicalSize(), e.physicalExact(), e.createdMs(), e.modifiedMs(),
                                e.accessedMs(), e.accessedMs(), now, e.linkCount(), e.altStreams(), flags,
                                e.nativeCompressed(), false, now, e.nativeCompressed() ? 1 : 0,
                                e.nativeCompressed() ? 1 : 0, e.nativeCompressed() ? 1 : 0, false, generation,
                                movedFrom);
                        update.executeUpdate();
                        mov++;
                        continue;
                    }
                    Sql.bind(insert, rootId, pathStr, key, e.parentKey(), e.relPath(), e.name(), nameLc, e.extension(),
                            e.category(), e.directory(), e.special(), e.fileKey(), e.volumeId(), e.logicalSize(),
                            e.physicalSize(), e.physicalExact(), e.createdMs(), e.modifiedMs(), e.accessedMs(),
                            e.accessedMs(), now, now, e.linkCount(), e.altStreams(), flags, e.nativeCompressed(),
                            e.nativeCompressed() ? OptStatus.EXTERNALLY_COMPRESSED.name() : OptStatus.NONE.name(),
                            generation);
                    insert.executeUpdate();
                    ins++;
                }
            }
            return new UpsertStats(ins, upd, mov, chg);
        });
    }

    /**
     * True when every stored column that a full update would write already holds the scanned value, and the
     * optimization-status transitions of the full update would leave the status as it is.
     */
    private static boolean unchanged(ResultSet rs, long rootId, String path, String flags, ScannedEntry e)
            throws SQLException {
        if (rs.getLong(6) != rootId || !path.equals(rs.getString(7)) || !Objects.equals(e.parentKey(), rs.getString(8))
                || !e.relPath().equals(rs.getString(9)) || !e.name().equals(rs.getString(10))
                || !Objects.equals(e.extension(), rs.getString(11)) || !Objects.equals(e.category(), rs.getString(12))
                || (rs.getInt(13) == 1) != e.directory() || (rs.getInt(14) == 1) != e.special()
                || !Objects.equals(e.fileKey(), rs.getString(15)) || !Objects.equals(e.volumeId(), rs.getString(16))
                || rs.getLong(17) != e.physicalSize() || (rs.getInt(18) == 1) != e.physicalExact()
                || rs.getLong(19) != e.createdMs() || rs.getLong(4) != e.accessedMs()
                || rs.getInt(20) != e.linkCount() || rs.getInt(21) != e.altStreams() || !flags.equals(rs.getString(22))
                || (rs.getInt(23) == 1) != e.nativeCompressed() || rs.getInt(25) != 1) {
            return false;
        }
        String status = rs.getString(24);
        return e.nativeCompressed() ? !"NONE".equals(status)
                : !("EXTERNALLY_COMPRESSED".equals(status) || "NATIVE_COMPRESSED".equals(status));
    }

    /**
     * Decides which last-access time is trustworthy user evidence. A newer access time that falls inside
     * a window in which IdleStow itself read the file is ignored, keeping the previous trusted value.
     */
    private long trustedAccess(Connection c, long itemId, long oldAccess, long oldTrusted, long newAccess)
            throws SQLException {
        if (newAccess <= 0) return oldTrusted;
        if (newAccess == oldAccess) return oldTrusted > 0 ? oldTrusted : newAccess;
        long selfReads = Sql.scalarLong(c, "SELECT COUNT(*) FROM activity_evidence WHERE item_id=? AND kind='SELF_READ'"
                        + " AND ? BETWEEN event_ms - ? AND COALESCE(end_ms, event_ms) + ?",
                itemId, newAccess, SELF_READ_BEFORE_MS, SELF_READ_AFTER_MS);
        return selfReads > 0 ? oldTrusted : newAccess;
    }

    private Long findMoveSource(PreparedStatement move, long rootId, long generation, ScannedEntry e)
            throws SQLException {
        if (e.fileKey() == null || e.directory() || e.linkCount() > 1) return null;
        Sql.bind(move, e.fileKey(), rootId, generation);
        List<Object[]> cands = new ArrayList<>(1);
        try (ResultSet rs = move.executeQuery()) {
            while (rs.next()) cands.add(new Object[]{rs.getLong(1), rs.getString(2)});
        }
        for (Object[] cand : cands) {
            Path old = Path.of((String) cand[1]);
            // Only a move if the old path is really gone (otherwise it may be a hard link or a stale key).
            if (Files.notExists(old, LinkOption.NOFOLLOW_LINKS)) return (Long) cand[0];
        }
        return null;
    }

    private static String flags(Set<String> attrs) {
        return String.join(",", attrs);
    }

    /**
     * After a complete scan of {@code subtreeKey} (or the whole root when null), removes rows that were not seen.
     * Rows whose original was replaced by an archive are kept (marked not present) so they stay restorable.
     */
    public int finalizeScan(long rootId, long generation, String subtreeKey) throws SQLException {
        return db.txResult(c -> {
            String scope = subtreeKey == null ? "" : " AND (path_key=? OR path_key LIKE ? ESCAPE '\\')";
            List<Object> args = new ArrayList<>(List.of(rootId, generation));
            if (subtreeKey != null) {
                args.add(subtreeKey);
                args.add(Sql.likeEscape(subtreeKey.endsWith(sep()) ? subtreeKey : subtreeKey + sep()) + "%");
            }
            Sql.update(c, "UPDATE indexed_items SET present=0 WHERE root_id=? AND scan_generation<? AND opt_status='ARCHIVED'"
                    + scope, args.toArray());
            return Sql.update(c, "DELETE FROM indexed_items WHERE root_id=? AND scan_generation<? AND opt_status<>'ARCHIVED'"
                    + scope, args.toArray());
        });
    }

    private static String sep() {
        return PathUtil.isWindows() ? "\\" : "/";
    }

    /** Removes a single path (and descendants) that disappeared, keeping archived originals. */
    public void markRemoved(Path path) throws SQLException {
        String key = PathUtil.key(path);
        String like = Sql.likeEscape(key.endsWith(sep()) ? key : key + sep()) + "%";
        db.tx(c -> {
            Sql.update(c, "UPDATE indexed_items SET present=0 WHERE (path_key=? OR path_key LIKE ? ESCAPE '\\')"
                    + " AND opt_status='ARCHIVED'", key, like);
            Sql.update(c, "DELETE FROM indexed_items WHERE (path_key=? OR path_key LIKE ? ESCAPE '\\')"
                    + " AND opt_status<>'ARCHIVED'", key, like);
        });
    }

    // ---------------------------------------------------------------- evidence & classification

    static void addEvidence(Connection c, long itemId, String kind, long eventMs, Long endMs, String detail, String now)
            throws SQLException {
        Sql.update(c, "INSERT INTO activity_evidence(item_id, kind, event_ms, end_ms, detail, recorded_utc)"
                + " VALUES(?,?,?,?,?,?)", itemId, kind, eventMs, endMs, detail, now);
    }

    public void addEvidence(long itemId, String kind, long eventMs, Long endMs, String detail) throws SQLException {
        String now = clock.instant().toString();
        db.tx(c -> addEvidence(c, itemId, kind, eventMs, endMs, detail, now));
    }

    /** Records that IdleStow itself read a file between {@code startMs} and {@code endMs}. */
    public void recordSelfRead(long itemId, long startMs, long endMs) throws SQLException {
        addEvidence(itemId, "SELF_READ", startMs, endMs, "Read by IdleStow (hashing/verification) — not user activity");
    }

    /** Latest evidence timestamp of {@code kind}, or 0. */
    public long latestEvidence(long itemId, String kind) throws SQLException {
        return db.read(c -> Sql.scalarLong(c,
                "SELECT COALESCE(MAX(event_ms),0) FROM activity_evidence WHERE item_id=? AND kind=?", itemId, kind));
    }

    public void saveClassifications(Collection<Object[]> rows) throws SQLException {
        String now = clock.instant().toString();
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO activity_classifications(item_id, classification, confidence, last_activity_ms, idle_days,"
                            + " explanation, evaluated_utc) VALUES(?,?,?,?,?,?,?) ON CONFLICT(item_id) DO UPDATE SET"
                            + " classification=excluded.classification, confidence=excluded.confidence,"
                            + " last_activity_ms=excluded.last_activity_ms, idle_days=excluded.idle_days,"
                            + " explanation=excluded.explanation, evaluated_utc=excluded.evaluated_utc")) {
                for (Object[] r : rows) {
                    long id = (Long) r[0];
                    ActivityAssessment a = (ActivityAssessment) r[1];
                    Sql.bind(ps, id, a.classification(), a.confidence(), a.lastActivityMs(), a.idleDays(),
                            a.explanation(), now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    public Optional<ActivityAssessment> classification(long itemId) throws SQLException {
        return db.read(c -> Sql.one(c, "SELECT * FROM activity_classifications WHERE item_id=?",
                rs -> new ActivityAssessment(ActivityClass.valueOf(rs.getString("classification")),
                        Confidence.valueOf(rs.getString("confidence")), rs.getLong("last_activity_ms"),
                        rs.getLong("idle_days"), List.of(rs.getString("explanation"))), itemId));
    }

    // ---------------------------------------------------------------- simple queries & updates

    public Optional<IndexedItem> byId(long id) throws SQLException {
        return db.read(c -> Sql.one(c, "SELECT * FROM indexed_items WHERE id=?", ItemRepository::map, id));
    }

    public Optional<IndexedItem> byPath(Path p) throws SQLException {
        return db.read(c -> Sql.one(c, "SELECT * FROM indexed_items WHERE path_key=?", ItemRepository::map,
                PathUtil.key(p)));
    }

    /** Iterates files of a root in id order, page by page, to keep memory bounded. */
    public List<IndexedItem> filesPage(long rootId, long afterId, int limit) throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT * FROM indexed_items WHERE +root_id=? AND is_dir=0 AND present=1 AND id>?"
                + " ORDER BY id LIMIT ?", ItemRepository::map, rootId, afterId, limit));
    }

    /** Files under a directory (recursive), page by page. */
    public List<IndexedItem> descendantsPage(Path dir, long afterId, int limit) throws SQLException {
        String key = PathUtil.key(dir);
        String like = Sql.likeEscape(key.endsWith(sep()) ? key : key + sep()) + "%";
        return db.read(c -> Sql.list(c, "SELECT * FROM indexed_items WHERE path_key LIKE ? ESCAPE '\\' AND is_dir=0"
                + " AND present=1 AND id>? ORDER BY id LIMIT ?", ItemRepository::map, like, afterId, limit));
    }

    public void setStatus(long id, OptStatus status, Long operationId, String error) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE indexed_items SET opt_status=?, last_operation_id=COALESCE(?, last_operation_id),"
                + " error=? WHERE id=?", status, operationId, error, id));
    }

    /** Updates the measured physical state after an operation (without waiting for a rescan). */
    public void updateMeasured(long id, long logical, long physical, boolean exact, boolean nativeCompressed,
                               long modifiedMs, long accessedMs) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE indexed_items SET logical_size=?, physical_size=?, physical_exact=?,"
                        + " native_compressed=?, modified_ms=?, accessed_ms=?,"
                        + " attr_flags=CASE WHEN ? THEN (CASE WHEN attr_flags='' THEN 'compressed'"
                        + "   WHEN instr(attr_flags,'compressed')>0 THEN attr_flags ELSE attr_flags||',compressed' END)"
                        + "   ELSE trim(replace(replace(attr_flags,'compressed',''),',,',','),',') END WHERE id=?",
                logical, physical, exact, nativeCompressed, modifiedMs, accessedMs, nativeCompressed, id));
    }

    public void saveEstimate(long id, IndexedItem.Estimate e) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE indexed_items SET est_zip_ratio=?, est_ntfs_savings=?, est_zip_savings=?,"
                        + " est_zstd_savings=?, est_format=?, est_basis=?, est_for_size=?, est_for_modified_ms=? WHERE id=?",
                e.zipRatio(), e.ntfsSavings(), e.zipSavings(), e.zstdSavings(), e.detectedFormat(), e.basis(),
                e.forSize(), e.forModifiedMs(), id));
    }

    public void markArchivedOriginal(long id, long operationId) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE indexed_items SET opt_status='ARCHIVED', present=0, last_operation_id=?,"
                + " error=NULL WHERE id=?", operationId, id));
    }

    public void markRestored(long id) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE indexed_items SET opt_status='NONE', present=1, error=NULL WHERE id=?", id));
    }

    public long countFiles() throws SQLException {
        return db.read(c -> Sql.scalarLong(c, "SELECT COUNT(*) FROM indexed_items WHERE is_dir=0 AND present=1"));
    }

    /** Distinct file ids having the given status (bounded). */
    public List<IndexedItem> byStatus(OptStatus status, int limit) throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT * FROM indexed_items WHERE opt_status=? ORDER BY path_key LIMIT ?",
                ItemRepository::map, status, limit));
    }

    public static String joinIds(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
