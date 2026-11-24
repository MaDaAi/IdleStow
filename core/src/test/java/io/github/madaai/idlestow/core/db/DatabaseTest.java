package io.github.madaai.idlestow.core.db;

import io.github.madaai.idlestow.core.db.Database.DatabaseUnavailableException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {
    static long scalar(Database db, String sql, Object... args) throws Exception {
        return db.read(c -> Sql.scalarLong(c, sql, args));
    }

    @TempDir Path tmp;

    private static String sha(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    private static String pragma(Database db, String name) throws Exception {
        return db.read(c -> {
            try (Statement s = c.createStatement(); var rs = s.executeQuery("PRAGMA " + name)) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private static Set<String> tables(Database db) throws Exception {
        List<String> l = db.read(c -> Sql.list(c, "SELECT name FROM sqlite_master WHERE type='table'", rs -> rs.getString(1)));
        return new TreeSet<String>(l);
    }

    @Test
    void freshOpenAppliesMigrationOne() throws Exception {
        Path f = tmp.resolve("sub/dir/idlestow.db");
        try (Database db = Database.open(f)) {
            assertEquals(1, db.currentVersion());
            assertEquals("ok", db.quickCheck());
            Set<String> t = tables(db);
            for (String expected : List.of("schema_migrations", "settings", "monitored_roots", "indexed_items",
                    "exclusions", "pinned_items", "policies", "optimization_candidates", "jobs", "recovery_records")) {
                assertTrue(t.contains(expected), "missing table " + expected + " in " + t);
            }
        }
        assertTrue(Files.isRegularFile(f), "parent directories are created");
    }

    @Test
    void reopenIsIdempotentAndKeepsData() throws Exception {
        Path f = tmp.resolve("idlestow.db");
        try (Database db = Database.open(f)) {
            db.tx(c -> Sql.update(c, "INSERT INTO settings(key,value,updated_utc) VALUES('k','v','now')"));
        }
        for (int i = 0; i < 3; i++) {
            try (Database db = Database.open(f)) {
                assertEquals(1, db.currentVersion());
                assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM schema_migrations"));
                assertEquals("v", db.read(c -> Sql.one(c, "SELECT value FROM settings WHERE key='k'",
                        rs -> rs.getString(1)).orElse(null)));
            }
        }
    }

    @Test
    void pragmasAreWalForeignKeysAndFullSync() throws Exception {
        try (Database db = Database.open(tmp.resolve("idlestow.db"))) {
            assertEquals("wal", pragma(db, "journal_mode").toLowerCase());
            assertEquals("1", pragma(db, "foreign_keys"));
            assertEquals("2", pragma(db, "synchronous"), "synchronous=FULL");
        }
    }

    @Test
    void foreignKeysAreEnforced() throws Exception {
        try (Database db = Database.open(tmp.resolve("idlestow.db"))) {
            assertThrows(java.sql.SQLException.class, () -> db.tx(c -> Sql.update(c,
                    "INSERT INTO indexed_items(root_id, path, path_key, rel_path, name, name_lc, first_indexed_utc,"
                            + " last_scanned_utc, scan_generation) VALUES(999,'p','pk','r','n','n','x','x',1)")));
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM indexed_items"));
        }
    }

    @Test
    void transactionRollsBackOnFailure() throws Exception {
        try (Database db = Database.open(tmp.resolve("idlestow.db"))) {
            assertThrows(IllegalStateException.class, () -> db.tx(c -> {
                Sql.update(c, "INSERT INTO settings(key,value,updated_utc) VALUES('a','b','c')");
                throw new IllegalStateException("boom");
            }));
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM settings"));
            // connection still usable and back in auto-commit mode
            db.tx(c -> Sql.update(c, "INSERT INTO settings(key,value,updated_utc) VALUES('a','b','c')"));
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM settings"));
        }
    }

    // ---------------------------------------------------------------- splitStatements

    @Test
    void splitStatementsHandlesCommentsAndTrailingSemicolons() {
        String sql = """
                -- header comment
                CREATE TABLE a (
                    id INTEGER PRIMARY KEY, -- trailing comment
                    v TEXT
                );

                -- another
                CREATE INDEX ix ON a(v);
                INSERT INTO a(v) VALUES('x'); -- comment after semicolon
                CREATE TABLE b(id INTEGER)
                """;
        List<String> out = Database.splitStatements(sql);
        assertEquals(4, out.size(), out.toString());
        assertTrue(out.get(0).startsWith("CREATE TABLE a"));
        assertFalse(out.get(0).contains("trailing comment"));
        assertFalse(out.get(0).endsWith(";"));
        assertEquals("CREATE INDEX ix ON a(v)", out.get(1));
        assertEquals("INSERT INTO a(v) VALUES('x')", out.get(2));
        assertEquals("CREATE TABLE b(id INTEGER)", out.get(3), "a final statement without semicolon is kept");
    }

    @Test
    void splitStatementsOnEmptyAndCommentOnlyInput() {
        assertTrue(Database.splitStatements("").isEmpty());
        assertTrue(Database.splitStatements("\n\n  \n").isEmpty());
        assertTrue(Database.splitStatements("-- nothing\n-- here\n").isEmpty());
        assertTrue(Database.splitStatements(";\n;\n").isEmpty(), "bare semicolons yield no statements");
    }

    @Test
    void splitStatementsHandlesWindowsLineEndings() {
        List<String> out = Database.splitStatements("CREATE TABLE a(x);\r\nCREATE TABLE b(y);\r\n");
        assertEquals(List.of("CREATE TABLE a(x)", "CREATE TABLE b(y)"), out);
    }

    @Test
    void splitStatementsCanSplitTheShippedMigrationWithoutLeavingFragments() throws Exception {
        String sql = new String(Database.class.getResourceAsStream("/db/migration/V1__initial.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        List<String> out = Database.splitStatements(sql);
        assertTrue(out.size() > 20);
        for (String s : out) {
            assertTrue(s.startsWith("CREATE ") || s.startsWith("INSERT ") || s.startsWith("PRAGMA "), s);
            assertFalse(s.contains("--"), s);
        }
    }

    @Test
    void splitStatementsKeepsDashesInsideStringLiterals() {
        List<String> out = Database.splitStatements("INSERT INTO t(v) VALUES('a--b');\nINSERT INTO t(v) VALUES('c');\n");
        assertEquals(List.of("INSERT INTO t(v) VALUES('a--b')", "INSERT INTO t(v) VALUES('c')"), out);
    }

    @Test
    void splitStatementsKeepsTriggerBodiesTogether() {
        List<String> out = Database.splitStatements(
                "CREATE TRIGGER tr AFTER INSERT ON t BEGIN\n  UPDATE t SET v=1;\nEND;\nCREATE TABLE z(x);\n");
        assertEquals(2, out.size(), out.toString());
    }

    // ---------------------------------------------------------------- refusing unusable files

    @Test
    void refusesDatabaseNewerThanSupportedAndLeavesItUntouched() throws Exception {
        Path f = tmp.resolve("idlestow.db");
        try (Database db = Database.open(f)) {
            db.tx(c -> Sql.update(c, "INSERT INTO schema_migrations(version, description, applied_utc) VALUES(999,'future','x')"));
        }
        long size = Files.size(f);
        String hash = sha(f);
        DatabaseUnavailableException ex = assertThrows(DatabaseUnavailableException.class, () -> Database.open(f));
        assertTrue(ex.getMessage().toLowerCase().contains("newer"), ex.getMessage());
        assertTrue(Files.isRegularFile(f), "file must not be deleted");
        assertEquals(size, Files.size(f));
        assertEquals(hash, sha(f), "file content must be unchanged");
        assertFalse(Files.exists(Path.of(f + "-wal")) && Files.size(Path.of(f + "-wal")) > 0, "no uncommitted WAL left behind");
        // the connection was released: another process/handle can replace the file
        Files.move(f, tmp.resolve("moved.db"));
    }

    @Test
    void corruptFileIsRejectedNotDeletedNorModified() throws Exception {
        Path f = tmp.resolve("idlestow.db");
        byte[] junk = new byte[8192];
        new Random(7).nextBytes(junk);
        Files.write(f, junk);
        assertThrows(DatabaseUnavailableException.class, () -> Database.open(f));
        assertTrue(Files.isRegularFile(f), "file must not be deleted");
        assertArrayEqualsFile(junk, f);
        assertFalse(Files.exists(Path.of(f + "-wal")));
    }

    @Test
    void textFileMasqueradingAsDatabaseIsRejectedAndKept() throws Exception {
        Path f = tmp.resolve("idlestow.db");
        byte[] text = "this is not a database at all, it is a note the user saved here\n".repeat(200).getBytes();
        Files.write(f, text);
        assertThrows(DatabaseUnavailableException.class, () -> Database.open(f));
        assertArrayEqualsFile(text, f);
    }

    @Test
    void damagedPagesInsideARealDatabaseAreRejectedAndKept() throws Exception {
        Path f = tmp.resolve("idlestow.db");
        try (Database db = Database.open(f)) {
            db.checkpoint();
        }
        byte[] bytes = Files.readAllBytes(f);
        assertTrue(bytes.length > 3 * 4096, "schema should span several pages");
        // zero out several interior pages (page 1, the header + schema, stays valid)
        for (int i = 4096; i < Math.min(bytes.length, 4096 * 6); i++) bytes[i] = 0x5A;
        Files.write(f, bytes, StandardOpenOption.TRUNCATE_EXISTING);
        String before = sha(f);
        assertThrows(DatabaseUnavailableException.class, () -> Database.open(f));
        assertTrue(Files.isRegularFile(f));
        assertEquals(before, sha(f), "a damaged database must never be altered by a failed open");
    }

    @Test
    void emptyFileIsTreatedAsNewDatabase() throws Exception {
        Path f = tmp.resolve("idlestow.db");
        Files.createFile(f);
        try (Database db = Database.open(f)) {
            assertEquals(1, db.currentVersion());
        }
    }

    private static void assertArrayEqualsFile(byte[] expected, Path f) throws Exception {
        assertTrue(java.util.Arrays.equals(expected, Files.readAllBytes(f)), "file content changed");
    }
}
