package io.github.madaai.idlestow.core.db;

import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.platform.VolumeInfo;
import io.github.madaai.idlestow.core.util.PathUtil;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** Persistence for monitored roots and volume information. */
public final class RootRepository {
    private final Database db;
    private final Clock clock;

    public RootRepository(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    static MonitoredRoot map(ResultSet rs) throws SQLException {
        return new MonitoredRoot(rs.getLong("id"), Path.of(rs.getString("path")), rs.getString("display_name"),
                rs.getString("volume_id"), rs.getInt("enabled") == 1, rs.getInt("autonomy_enabled") == 1,
                rs.getInt("archival") == 1, rs.getString("authorized_utc"), rs.getString("revoked_utc"),
                rs.getString("last_scan_utc"), rs.getString("last_scan_status"), rs.getLong("scan_generation"));
    }

    public List<MonitoredRoot> all() throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT * FROM monitored_roots ORDER BY path", RootRepository::map));
    }

    public List<MonitoredRoot> active() throws SQLException {
        return db.read(c -> Sql.list(c,
                "SELECT * FROM monitored_roots WHERE enabled=1 AND revoked_utc IS NULL ORDER BY path",
                RootRepository::map));
    }

    public Optional<MonitoredRoot> byId(long id) throws SQLException {
        return db.read(c -> Sql.one(c, "SELECT * FROM monitored_roots WHERE id=?", RootRepository::map, id));
    }

    public long insert(Path path, String displayName, String volumeId) throws SQLException {
        return db.txResult(c -> Sql.insert(c,
                "INSERT INTO monitored_roots(path, path_key, display_name, volume_id, authorized_utc) VALUES(?,?,?,?,?)",
                PathUtil.normalize(path).toString(), PathUtil.key(path), displayName, volumeId,
                clock.instant().toString()));
    }

    /** Re-authorizes a previously revoked root. */
    public void reauthorize(long id) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE monitored_roots SET enabled=1, revoked_utc=NULL, authorized_utc=? WHERE id=?",
                clock.instant().toString(), id));
    }

    /** Revokes authorization immediately: no new operations may start under this root. */
    public void revoke(long id) throws SQLException {
        db.tx(c -> Sql.update(c,
                "UPDATE monitored_roots SET revoked_utc=?, autonomy_enabled=0 WHERE id=? AND revoked_utc IS NULL",
                clock.instant().toString(), id));
    }

    /** Removes the root and its index (cascade). Recovery records are kept. */
    public void delete(long id) throws SQLException {
        db.tx(c -> Sql.update(c, "DELETE FROM monitored_roots WHERE id=?", id));
    }

    public void setAutonomy(long id, boolean enabled) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE monitored_roots SET autonomy_enabled=? WHERE id=?", enabled, id));
    }

    public void setArchival(long id, boolean archival) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE monitored_roots SET archival=? WHERE id=?", archival, id));
    }

    public long nextGeneration(long id) throws SQLException {
        return db.txResult(c -> {
            Sql.update(c, "UPDATE monitored_roots SET scan_generation=scan_generation+1 WHERE id=?", id);
            return Sql.scalarLong(c, "SELECT scan_generation FROM monitored_roots WHERE id=?", id);
        });
    }

    public void recordScan(long id, String status) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE monitored_roots SET last_scan_utc=?, last_scan_status=? WHERE id=?",
                clock.instant().toString(), status, id));
    }

    public void upsertVolume(VolumeInfo v) throws SQLException {
        db.tx(c -> upsertVolume(c, v, clock.instant().toString()));
    }

    static void upsertVolume(Connection c, VolumeInfo v, String now) throws SQLException {
        Sql.update(c, "INSERT INTO filesystem_volumes(volume_id, root_path, fs_type, native_compression, native_note,"
                        + " cluster_size, read_only, remote, access_tracking, access_note, observed_utc)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(volume_id) DO UPDATE SET root_path=excluded.root_path,"
                        + " fs_type=excluded.fs_type, native_compression=excluded.native_compression,"
                        + " native_note=excluded.native_note, cluster_size=excluded.cluster_size,"
                        + " read_only=excluded.read_only, remote=excluded.remote, access_tracking=excluded.access_tracking,"
                        + " access_note=excluded.access_note, observed_utc=excluded.observed_utc",
                v.volumeId(), v.rootPath(), v.fileSystemType(), v.nativeCompression(), v.nativeCompressionNote(),
                v.clusterSize(), v.readOnly(), v.remote(), v.accessTracking(), v.accessTrackingNote(), now);
    }

    public Optional<VolumeInfo> volume(String volumeId) throws SQLException {
        return db.read(c -> Sql.one(c, "SELECT * FROM filesystem_volumes WHERE volume_id=?", rs -> new VolumeInfo(
                rs.getString("volume_id"), rs.getString("root_path"), rs.getString("fs_type"),
                rs.getInt("native_compression") == 1, rs.getString("native_note"), rs.getLong("cluster_size"),
                rs.getInt("read_only") == 1, rs.getInt("remote") == 1,
                VolumeInfo.AccessTracking.valueOf(rs.getString("access_tracking")), rs.getString("access_note")),
                volumeId));
    }
}
