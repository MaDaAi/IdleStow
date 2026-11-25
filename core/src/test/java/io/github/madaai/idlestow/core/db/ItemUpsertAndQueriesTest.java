package io.github.madaai.idlestow.core.db;

import io.github.madaai.idlestow.core.index.ScannedEntry;
import io.github.madaai.idlestow.core.util.PathUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behaviour of the batched upsert (including the unchanged-row fast path) and of the cached dashboard queries. */
class ItemUpsertAndQueriesTest {
    @TempDir Path tmp;
    Database db;
    ItemRepository items;
    Queries queries;
    Path root;
    long rootId;
    long gen;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(tmp.resolve("db/idlestow.db"));
        items = new ItemRepository(db, Clock.systemUTC());
        queries = new Queries(db);
        RootRepository roots = new RootRepository(db, Clock.systemUTC());
        root = Files.createDirectories(tmp.resolve("Docs")).toAbsolutePath();
        rootId = roots.insert(root, "Docs", "V1");
        gen = roots.nextGeneration(rootId);
    }

    @AfterEach
    void tearDown() { db.close(); }

    private ScannedEntry file(String name, String fileKey, long size, long mtime, long physical, boolean nativeComp,
                              Set<String> attrs) {
        Path p = root.resolve(name);
        return new ScannedEntry(p, name, PathUtil.key(root), name, "txt", "Documents", false, false, fileKey, "V1",
                size, physical, true, 1_000, mtime, 2_000, 1, 0, attrs, nativeComp);
    }

    private long scalar(String sql) throws Exception {
        return db.read(c -> Sql.scalarLong(c, sql));
    }

    @Test
    void unchangedRescanOnlyAdvancesTheGeneration() throws Exception {
        ScannedEntry e = file("a.txt", "K1", 100, 5_000, 4096, false, Set.of());
        assertEquals(1, items.upsertBatch(rootId, gen, List.of(e)).inserted());
        long id = scalar("SELECT id FROM indexed_items WHERE name='a.txt'");
        db.tx(c -> Sql.update(c, "UPDATE indexed_items SET est_zip_savings=77 WHERE id=?", id));

        ItemRepository.UpsertStats st = items.upsertBatch(rootId, gen + 1, List.of(e));
        assertEquals(0, st.inserted());
        assertEquals(1, st.updated());
        assertEquals(0, st.changed());
        assertEquals(gen + 1, scalar("SELECT scan_generation FROM indexed_items WHERE id=" + id));
        assertEquals(77, scalar("SELECT est_zip_savings FROM indexed_items WHERE id=" + id));
        assertEquals(0, scalar("SELECT COUNT(*) FROM activity_evidence"));
        assertEquals(0, items.finalizeScan(rootId, gen + 1, null), "nothing is reconciled away");
        assertEquals(1, items.countFiles());
    }

    @Test
    void anyDifferenceIsStillWrittenByARescan() throws Exception {
        items.upsertBatch(rootId, gen, List.of(file("a.txt", "K1", 100, 5_000, 4096, false, Set.of())));
        long id = scalar("SELECT id FROM indexed_items WHERE name='a.txt'");

        // allocation changed only
        items.upsertBatch(rootId, gen + 1, List.of(file("a.txt", "K1", 100, 5_000, 8192, false, Set.of())));
        assertEquals(8192, scalar("SELECT physical_size FROM indexed_items WHERE id=" + id));
        // attributes changed only
        items.upsertBatch(rootId, gen + 2, List.of(file("a.txt", "K1", 100, 5_000, 8192, false, Set.of("readonly"))));
        assertEquals(1, scalar("SELECT COUNT(*) FROM indexed_items WHERE id=" + id + " AND attr_flags='readonly'"));
        // identity changed only
        items.upsertBatch(rootId, gen + 3, List.of(file("a.txt", "K2", 100, 5_000, 8192, false, Set.of("readonly"))));
        assertEquals(1, scalar("SELECT COUNT(*) FROM indexed_items WHERE id=" + id + " AND file_key='K2'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM activity_evidence"));
        // content changed: evidence is recorded and the change counted
        ItemRepository.UpsertStats st = items.upsertBatch(rootId, gen + 4,
                List.of(file("a.txt", "K2", 150, 9_000, 8192, false, Set.of("readonly"))));
        assertEquals(1, st.changed());
        assertEquals(1, scalar("SELECT COUNT(*) FROM activity_evidence WHERE kind='MODIFIED_OBSERVED'"));
        assertEquals(150, scalar("SELECT logical_size FROM indexed_items WHERE id=" + id));
    }

    @Test
    void presenceAndNativeCompressionTransitionsAreUnchanged() throws Exception {
        items.upsertBatch(rootId, gen, List.of(file("a.txt", "K1", 100, 5_000, 4096, false, Set.of())));
        long id = scalar("SELECT id FROM indexed_items WHERE name='a.txt'");
        // compressed outside IdleStow
        items.upsertBatch(rootId, gen + 1, List.of(file("a.txt", "K1", 100, 5_000, 1024, true, Set.of("compressed"))));
        assertEquals(1, scalar("SELECT COUNT(*) FROM indexed_items WHERE id=" + id
                + " AND opt_status='EXTERNALLY_COMPRESSED'"));
        // unchanged: stays
        items.upsertBatch(rootId, gen + 2, List.of(file("a.txt", "K1", 100, 5_000, 1024, true, Set.of("compressed"))));
        assertEquals(1, scalar("SELECT COUNT(*) FROM indexed_items WHERE id=" + id
                + " AND opt_status='EXTERNALLY_COMPRESSED'"));
        // decompressed outside IdleStow
        items.upsertBatch(rootId, gen + 3, List.of(file("a.txt", "K1", 100, 5_000, 4096, false, Set.of())));
        assertEquals(1, scalar("SELECT COUNT(*) FROM indexed_items WHERE id=" + id + " AND opt_status='NONE'"));
        // an IdleStow-compressed file that is no longer compressed reverts to NONE; present is restored
        db.tx(c -> Sql.update(c, "UPDATE indexed_items SET opt_status='NATIVE_COMPRESSED', native_compressed=1,"
                + " present=0 WHERE id=?", id));
        items.upsertBatch(rootId, gen + 4, List.of(file("a.txt", "K1", 100, 5_000, 4096, false, Set.of())));
        assertEquals(1, scalar("SELECT COUNT(*) FROM indexed_items WHERE id=" + id
                + " AND opt_status='NONE' AND present=1"));
    }

    @Test
    void dashboardTotalsFollowWritesAndCountHardLinksOnce() throws Exception {
        assertEquals(0, queries.totals().files());
        items.upsertBatch(rootId, gen, List.of(
                file("a.txt", "K1", 100, 5_000, 4096, false, Set.of()),
                file("a-link.txt", "K1", 100, 5_000, 4096, false, Set.of()),
                file("b.txt", "K2", 300, 5_000, -1, false, Set.of()),
                file("c.txt", null, 50, 5_000, 4096, false, Set.of()),
                file("d.txt", null, 70, 5_000, 4096, false, Set.of())));
        Queries.Totals t = queries.totals();
        assertEquals(5, t.files());
        assertEquals(100 + 300 + 50 + 70, t.logical(), "hard links once, identity-less files individually");
        assertEquals(4096 + 300 + 4096 + 4096, t.physical(), "unknown allocation falls back to the logical size");
        assertEquals(t, queries.totals(), "repeat call returns the same data");

        List<Object[]> cats = queries.byCategory();
        assertEquals(1, cats.size());
        assertEquals(5L, cats.get(0)[1]);
        cats.get(0)[1] = 99L; // callers cannot corrupt the cache
        assertEquals(5L, queries.byCategory().get(0)[1]);

        items.markRemoved(root.resolve("c.txt"));
        assertEquals(4, queries.totals().files(), "a write invalidates the cached result");
        assertEquals(4L, queries.byCategory().get(0)[1]);
    }

    @Test
    void filesPageWalksOneRootInIdOrderWithoutLoopingOrSkipping() throws Exception {
        RootRepository roots = new RootRepository(db, Clock.systemUTC());
        Path other = Files.createDirectories(tmp.resolve("Other")).toAbsolutePath();
        long otherId = roots.insert(other, "Other", "V1");
        for (int i = 0; i < 25; i++) {
            items.upsertBatch(rootId, gen, List.of(file("f" + i + ".txt", "K" + i, 10, 5_000, 4096, false, Set.of())));
            Path p = other.resolve("o" + i + ".txt");
            items.upsertBatch(otherId, 1, List.of(new ScannedEntry(p, "o" + i + ".txt", PathUtil.key(other),
                    "o" + i + ".txt", "txt", "Documents", false, false, "O" + i, "V1", 10, 4096, true, 1, 1, 1, 1, 0,
                    Set.of(), false)));
        }
        long after = 0;
        int seen = 0;
        while (true) {
            List<io.github.madaai.idlestow.core.model.IndexedItem> page = items.filesPage(rootId, after, 7);
            if (page.isEmpty()) break;
            for (var it : page) {
                assertEquals(rootId, it.rootId());
                assertTrue(it.id() > after);
                after = it.id();
                seen++;
            }
        }
        assertEquals(25, seen);
    }
}
