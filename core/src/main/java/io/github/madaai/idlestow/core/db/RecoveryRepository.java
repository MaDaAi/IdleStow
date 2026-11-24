package io.github.madaai.idlestow.core.db;

import io.github.madaai.idlestow.core.model.Enums.RecoveryKind;
import io.github.madaai.idlestow.core.model.Enums.RecoveryStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** Recovery records: one per optimization that can (or could) be reversed. */
public final class RecoveryRepository {
    private final Database db;
    private final Clock clock;

    public RecoveryRepository(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public record Record(long id, String operationUuid, Long itemId, String rootPath, String originalPath,
                         String fileKey, RecoveryKind kind, RecoveryStatus status, String archivePath,
                         String archiveFormat, String entryName, Long archiveSize, String retainedOriginalPath,
                         Long retainedSize, String sha256, Long size, Long modifiedMs, String manifestPath,
                         String createdUtc, String retainUntilUtc, String lastVerifiedUtc, String note) { }

    static Record map(ResultSet rs) throws SQLException {
        return new Record(rs.getLong("id"), rs.getString("operation_uuid"), Sql.getLong(rs, "item_id"),
                rs.getString("root_path"), rs.getString("original_path"), rs.getString("file_key"),
                RecoveryKind.valueOf(rs.getString("kind")), RecoveryStatus.valueOf(rs.getString("status")),
                rs.getString("archive_path"), rs.getString("archive_format"), rs.getString("entry_name"),
                Sql.getLong(rs, "archive_size"), rs.getString("retained_original_path"),
                Sql.getLong(rs, "retained_size"), rs.getString("sha256"), Sql.getLong(rs, "size"),
                Sql.getLong(rs, "modified_ms"), rs.getString("manifest_path"), rs.getString("created_utc"),
                rs.getString("retain_until_utc"), rs.getString("last_verified_utc"), rs.getString("note"));
    }

    public long insert(Record r) throws SQLException {
        return db.txResult(c -> Sql.insert(c, "INSERT INTO recovery_records(operation_uuid, item_id, root_path,"
                        + " original_path, file_key, kind, status, archive_path, archive_format, entry_name, archive_size,"
                        + " retained_original_path, retained_size, sha256, size, modified_ms, manifest_path, created_utc,"
                        + " retain_until_utc, last_verified_utc, note) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.operationUuid(), r.itemId(), r.rootPath(), r.originalPath(), r.fileKey(), r.kind(), r.status(),
                r.archivePath(), r.archiveFormat(), r.entryName(), r.archiveSize(), r.retainedOriginalPath(),
                r.retainedSize(), r.sha256(), r.size(), r.modifiedMs(), r.manifestPath(),
                r.createdUtc() != null ? r.createdUtc() : clock.instant().toString(), r.retainUntilUtc(),
                r.lastVerifiedUtc(), r.note()));
    }

    public void setStatus(long id, RecoveryStatus status, String note) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE recovery_records SET status=?, note=COALESCE(?, note) WHERE id=?",
                status, note, id));
    }

    public void retainedPurged(long id) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE recovery_records SET status='ORIGINAL_PURGED', retained_original_path=NULL,"
                + " retained_size=0, note=? WHERE id=?", "Retained original removed after re-verifying the archive", id));
    }

    public void verified(long id) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE recovery_records SET last_verified_utc=? WHERE id=?",
                clock.instant().toString(), id));
    }

    public Optional<Record> byId(long id) throws SQLException {
        return db.read(c -> Sql.one(c, "SELECT * FROM recovery_records WHERE id=?", RecoveryRepository::map, id));
    }

    /** Latest record still in effect for an item (ACTIVE, ORIGINAL_PURGED, REVIEW, DAMAGED, MISSING). */
    public Optional<Record> currentForItem(long itemId) throws SQLException {
        return db.read(c -> Sql.one(c, "SELECT * FROM recovery_records WHERE item_id=? AND status IN"
                        + " ('ACTIVE','ORIGINAL_PURGED','REVIEW','DAMAGED','MISSING') ORDER BY id DESC LIMIT 1",
                RecoveryRepository::map, itemId));
    }

    public List<Record> list(boolean includeClosed, int limit) throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT * FROM recovery_records"
                        + (includeClosed ? "" : " WHERE status NOT IN ('RESTORED','REMOVED')")
                        + " ORDER BY id DESC LIMIT ?", RecoveryRepository::map, limit));
    }

    /** Bytes held by retained originals (recovery storage overhead). */
    public long retainedBytes() throws SQLException {
        return db.read(c -> Sql.scalarLong(c, "SELECT COALESCE(SUM(retained_size),0) FROM recovery_records"
                + " WHERE status='ACTIVE' AND retained_original_path IS NOT NULL"));
    }

    public long countNeedingAttention() throws SQLException {
        return db.read(c -> Sql.scalarLong(c, "SELECT COUNT(*) FROM recovery_records WHERE status IN"
                + " ('REVIEW','DAMAGED','MISSING')"));
    }

    /** Records whose retained original may be purged (retention expired). */
    public List<Record> expiredRetention(String nowUtc) throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT * FROM recovery_records WHERE status='ACTIVE' AND kind='ARCHIVE_REPLACE'"
                + " AND retained_original_path IS NOT NULL AND retain_until_utc IS NOT NULL AND retain_until_utc <= ?",
                RecoveryRepository::map, nowUtc));
    }
}
