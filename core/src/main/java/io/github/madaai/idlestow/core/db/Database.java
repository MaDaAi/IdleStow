package io.github.madaai.idlestow.core.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the single SQLite connection used by IdleStow.
 *
 * <p>SQLite connections are not safe for unsynchronized concurrent use, so every access goes
 * through {@link #read} / {@link #tx}, serialized by a fair lock. Writes are always in explicit
 * transactions. WAL journaling with {@code synchronous=FULL} favours durability over speed.
 *
 * <p>If the database cannot be opened or fails its health check, a {@link DatabaseUnavailableException}
 * is thrown and the file is left untouched; the UI offers an explicit recovery workflow.
 */
public final class Database implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    /** Ordered migrations. Never edit a released migration; add a new one. */
    static final List<String[]> MIGRATIONS = List.<String[]>of(
            new String[]{"1", "initial schema", "/db/migration/V1__initial.sql"}
    );

    private final Path file;
    private final Connection conn;
    private final ReentrantLock lock = new ReentrantLock(true);

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection c) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlConsumer {
        void accept(Connection c) throws SQLException;
    }

    private Database(Path file, Connection conn) {
        this.file = file;
        this.conn = conn;
    }

    /** Opens (creating if needed) the database at {@code file} and applies pending migrations. */
    public static Database open(Path file) throws DatabaseUnavailableException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new DatabaseUnavailableException("Cannot create the data directory for " + file, e);
        }
        Connection c;
        try {
            c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        } catch (SQLException e) {
            throw new DatabaseUnavailableException("Cannot open database " + file + ": " + e.getMessage(), e);
        }
        Database db = new Database(file, c);
        try {
            db.configure();
            String health = db.quickCheck();
            if (!"ok".equalsIgnoreCase(health)) {
                db.closeQuietly();
                throw new DatabaseUnavailableException("Database failed integrity check: " + health, null);
            }
            db.migrate();
        } catch (SQLException e) {
            db.closeQuietly();
            throw new DatabaseUnavailableException("Database initialisation failed: " + e.getMessage(), e);
        }
        return db;
    }

    private void configure() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA busy_timeout = 10000");
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA synchronous = FULL");
            s.execute("PRAGMA temp_store = MEMORY");
        }
        conn.setAutoCommit(true);
    }

    /** Runs {@code PRAGMA quick_check}; returns "ok" when healthy. */
    public String quickCheck() throws SQLException {
        return read(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA quick_check")) {
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    if (!sb.isEmpty()) sb.append("; ");
                    sb.append(rs.getString(1));
                }
                return sb.toString();
            }
        });
    }

    private void migrate() throws SQLException {
        tx(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, "
                        + "description TEXT NOT NULL, applied_utc TEXT NOT NULL)");
            }
        });
        int current = currentVersion();
        int latest = Integer.parseInt(MIGRATIONS.get(MIGRATIONS.size() - 1)[0]);
        if (current > latest) {
            throw new SQLException("Database schema version " + current + " is newer than this IdleStow build ("
                    + latest + "). Refusing to open to avoid damaging it.");
        }
        for (String[] m : MIGRATIONS) {
            int v = Integer.parseInt(m[0]);
            if (v <= current) continue;
            String sql = loadResource(m[2]);
            LOG.info("Applying database migration V{} ({})", v, m[1]);
            tx(c -> {
                try (Statement s = c.createStatement()) {
                    for (String stmt : splitStatements(sql)) s.execute(stmt);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO schema_migrations(version, description, applied_utc) VALUES (?,?,?)")) {
                    ps.setInt(1, v);
                    ps.setString(2, m[1]);
                    ps.setString(3, Instant.now().toString());
                    ps.executeUpdate();
                }
            });
        }
    }

    public int currentVersion() throws SQLException {
        return read(c -> {
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : sql.split("\n")) {
            String t = line.strip();
            if (t.startsWith("--") || t.isEmpty()) continue;
            int comment = line.indexOf("--");
            cur.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
            if (t.endsWith(";") || (comment >= 0 && line.substring(0, comment).strip().endsWith(";"))) {
                String s = cur.toString().strip();
                if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
                if (!s.isBlank()) out.add(s);
                cur.setLength(0);
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().strip());
        return out;
    }

    private static String loadResource(String name) throws SQLException {
        try (InputStream in = Database.class.getResourceAsStream(name)) {
            if (in == null) throw new SQLException("Missing migration resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLException("Cannot read migration " + name, e);
        }
    }

    /** Executes a read (or a single auto-committed statement) under the connection lock. */
    public <T> T read(SqlFunction<T> fn) throws SQLException {
        lock.lock();
        try {
            return fn.apply(conn);
        } finally {
            lock.unlock();
        }
    }

    /** Executes {@code fn} in a transaction; rolls back on any exception. */
    public <T> T txResult(SqlFunction<T> fn) throws SQLException {
        lock.lock();
        try {
            conn.setAutoCommit(false);
            try {
                T r = fn.apply(conn);
                conn.commit();
                return r;
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback();
                } catch (SQLException re) {
                    e.addSuppressed(re);
                }
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } finally {
            lock.unlock();
        }
    }

    public void tx(SqlConsumer fn) throws SQLException {
        txResult(c -> {
            fn.accept(c);
            return null;
        });
    }

    public Path file() { return file; }

    /** Checkpoints the WAL so the main database file is self-contained (used before export/backup). */
    public void checkpoint() throws SQLException {
        read(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            return null;
        });
    }

    private void closeQuietly() {
        try {
            conn.close();
        } catch (SQLException e) {
            LOG.warn("Error closing database", e);
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closeQuietly();
        } finally {
            lock.unlock();
        }
    }

    /** Thrown when the database cannot be used. The file is never deleted automatically. */
    public static final class DatabaseUnavailableException extends Exception {
        public DatabaseUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
