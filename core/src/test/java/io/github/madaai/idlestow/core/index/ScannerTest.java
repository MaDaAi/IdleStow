package io.github.madaai.idlestow.core.index;

import io.github.madaai.idlestow.core.db.Database;
import io.github.madaai.idlestow.core.db.ItemRepository;
import io.github.madaai.idlestow.core.db.RootRepository;
import io.github.madaai.idlestow.core.db.Sql;
import io.github.madaai.idlestow.core.index.Scanner.Progress;
import io.github.madaai.idlestow.core.index.Scanner.Result;
import io.github.madaai.idlestow.core.model.Enums.OptStatus;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.platform.FileInspector;
import io.github.madaai.idlestow.core.platform.NativeFileState;
import io.github.madaai.idlestow.core.platform.VolumeInfo;
import io.github.madaai.idlestow.core.testsupport.FakePlatform;
import io.github.madaai.idlestow.core.testsupport.LinkSupport;
import io.github.madaai.idlestow.core.testsupport.TestEnv;
import io.github.madaai.idlestow.core.util.CancellationToken;
import io.github.madaai.idlestow.core.util.PathUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScannerTest {
    @TempDir Path tmp;
    Database db;
    ItemRepository items;
    RootRepository roots;
    FakePlatform platform;
    Scanner scanner;
    Path rootDir;
    MonitoredRoot root;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(tmp.resolve("db/idlestow.db"));
        items = new ItemRepository(db, Clock.systemUTC());
        roots = new RootRepository(db, Clock.systemUTC());
        platform = new FakePlatform();
        rootDir = Files.createDirectories(tmp.resolve("Documents"));
        root = newRoot(rootDir);
        scanner = new Scanner(platform.fileInspector(), items, roots);
    }

    @AfterEach
    void tearDown() { db.close(); }

    private MonitoredRoot newRoot(Path dir) throws Exception {
        long id = roots.insert(dir, dir.getFileName().toString(), "FAKEVOL1");
        return roots.byId(id).orElseThrow();
    }

    private Result scan() throws Exception {
        return scanner.scan(roots.byId(root.id()).orElseThrow(), null, new CancellationToken(), p -> { });
    }

    private Path write(String rel, String content) throws Exception {
        Path f = rootDir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        return f;
    }

    private long rowCount() throws Exception {
        return db.read(c -> Sql.scalarLong(c, "SELECT COUNT(*) FROM indexed_items"));
    }

    // ------------------------------------------------------------------ basic shapes

    @Test
    void emptyDirectory() throws Exception {
        Result r = scan();
        assertTrue(r.completed());
        assertEquals(0, r.files());
        assertEquals(1, r.dirs(), "the root itself is recorded");
        assertEquals(0, r.errors());
        assertEquals(0, items.countFiles());
        assertEquals("OK", r.status().substring(0, 2));
        assertTrue(roots.byId(root.id()).orElseThrow().lastScanStatus().startsWith("OK"));
    }

    @Test
    void emptyFile() throws Exception {
        Path f = write("empty.txt", "");
        Result r = scan();
        assertEquals(1, r.files());
        assertEquals(0, r.bytes());
        IndexedItem it = items.byPath(f).orElseThrow();
        assertEquals(0, it.logicalSize());
        assertEquals("txt", it.extension());
        assertEquals("Documents", it.category());
        assertFalse(it.directory());
        assertTrue(it.present());
        assertEquals("empty.txt", it.relPath());
    }

    @Test
    void deeplyNestedTree() throws Exception {
        StringBuilder rel = new StringBuilder();
        for (int i = 0; i < 50; i++) rel.append((char) ('a' + i % 26)).append('/');
        Path leaf = write(rel + "leaf.txt", "deep");
        Result r = scan();
        assertTrue(r.completed());
        assertEquals(1, r.files());
        assertEquals(51, r.dirs(), "root + 50 nested folders");
        IndexedItem it = items.byPath(leaf).orElseThrow();
        assertEquals("leaf.txt", it.name());
        assertEquals(50, it.relPath().split("[\\\\/]").length - 1);
    }

    @Test
    void unicodeSpacesAndLongNames() throws Exception {
        List<String> names = List.of("Ünïcödé 文件 файл.txt",
                "name   with    spaces.txt", "trailing.dots.name.tar.gz", "😀 emoji.md",
                "x".repeat(200) + ".txt", "UPPER.TXT");
        List<Path> files = new ArrayList<>();
        for (String n : names) files.add(write("dir é/sub folder/" + n, n));
        Result r = scan();
        assertEquals(names.size(), r.files());
        for (int i = 0; i < names.size(); i++) {
            IndexedItem it = items.byPath(files.get(i)).orElseThrow(() -> new AssertionError("missing"));
            assertEquals(names.get(i), it.name());
        }
        assertEquals("txt", items.byPath(files.get(5)).orElseThrow().extension(), "extensions are lower-cased");
        assertEquals("gz", items.byPath(files.get(2)).orElseThrow().extension());
        assertEquals("Archives", items.byPath(files.get(2)).orElseThrow().category());
    }

    @Test
    void fiveThousandFilesAreIndexedQuicklyAndRescanIsIdempotent() throws Exception {
        for (int d = 0; d < 50; d++) {
            Path dir = Files.createDirectories(rootDir.resolve("d" + d));
            for (int i = 0; i < 100; i++) Files.writeString(dir.resolve("f" + i + ".txt"), "x" + i);
        }
        long t0 = System.nanoTime();
        Result r = scan();
        Duration took = Duration.ofNanos(System.nanoTime() - t0);
        assertTrue(took.getSeconds() < 30, "scan of 5,000 files took " + took);
        assertTrue(r.completed());
        assertEquals(5000, r.files());
        assertEquals(5000, items.countFiles());
        assertEquals(5000 + 50 + 1, rowCount());
        assertEquals(5051, r.inserted());

        Result again = scan();
        assertEquals(0, again.inserted());
        assertEquals(0, again.changed());
        assertEquals(0, again.removed());
        assertEquals(5000, again.files());
        assertEquals(5051, rowCount(), "no duplicate rows after a rescan");
    }

    // ------------------------------------------------------------------ tolerance

    @Test
    void inaccessibleEntriesAreToleratedAndCounted() throws Exception {
        Path good = write("good.txt", "good");
        Path bad = write("bad.txt", "bad");
        FileInspector real = platform.fileInspector();
        FileInspector flaky = new FileInspector() {
            @Override public VolumeInfo volumeOf(Path path) throws IOException { return real.volumeOf(path); }

            @Override
            public NativeFileState inspect(Path path) throws IOException {
                if (path.getFileName().toString().equals("bad.txt")) throw new IOException("Access is denied");
                return real.inspect(path);
            }

            @Override public Boolean isLockedByOtherProcess(Path path) { return real.isLockedByOtherProcess(path); }
        };
        Result r = new Scanner(flaky, items, roots).scan(root, null, new CancellationToken(), p -> { });
        assertTrue(r.completed());
        assertEquals(1, r.errors());
        assertEquals(2, r.files());
        assertTrue(items.byPath(good).isPresent());
        IndexedItem badItem = items.byPath(bad).orElseThrow();
        assertEquals(null, badItem.fileKey(), "no identity when inspection failed");
        assertTrue(r.status().contains("inaccessible"), r.status());
    }

    @Test
    void scanOfMissingRootFailsCleanlyAndRecordsStatus() throws Exception {
        Path gone = tmp.resolve("Gone");
        MonitoredRoot missing = newRoot(gone);
        assertThrows(IOException.class, () -> scanner.scan(missing, null, new CancellationToken(), p -> { }));
        assertTrue(roots.byId(missing.id()).orElseThrow().lastScanStatus().startsWith("Unavailable"));
    }

    @Test
    void scanStartOutsideRootIsRejected() throws Exception {
        Path other = Files.createDirectories(tmp.resolve("Other"));
        assertThrows(IOException.class, () -> scanner.scan(root, other, new CancellationToken(), p -> { }));
        Path trap = Files.createDirectories(tmp.resolve("Documents2"));
        assertThrows(IOException.class, () -> scanner.scan(root, trap, new CancellationToken(), p -> { }));
    }

    // ------------------------------------------------------------------ incremental & reconciliation

    @Test
    void filesAddedBetweenScansAreIndexedIncrementally() throws Exception {
        write("a.txt", "a");
        write("b.txt", "b");
        Result first = scan();
        assertEquals(3, first.inserted());
        write("sub/c.txt", "c");
        write("d.txt", "d");
        Result second = scan();
        assertEquals(3, second.inserted(), "d.txt, sub/ and sub/c.txt are new");
        assertEquals(0, second.removed());
        assertEquals(4, items.countFiles());
    }

    @Test
    void modifiedFileIsReportedAsChangedAndEvidenceRecorded() throws Exception {
        Path f = write("a.txt", "short");
        scan();
        long id = items.byPath(f).orElseThrow().id();
        Files.writeString(f, "a much longer content than before");
        Result r = scan();
        assertEquals(1, r.changed());
        assertEquals(0, r.inserted());
        assertTrue(items.latestEvidence(id, "MODIFIED_OBSERVED") > 0);
        assertEquals(33, items.byPath(f).orElseThrow().logicalSize());
    }

    @Test
    void removedFilesAreReconciledAfterACompletedScan() throws Exception {
        Path keep = write("keep.txt", "k");
        Path gone = write("sub/gone.txt", "g");
        scan();
        assertEquals(2, items.countFiles());
        Files.delete(gone);
        Files.delete(gone.getParent());
        Result r = scan();
        assertEquals(2, r.removed(), "the file and its folder");
        assertTrue(items.byPath(gone).isEmpty());
        assertTrue(items.byPath(keep).isPresent());
        assertEquals(1, items.countFiles());
    }

    @Test
    void removedFilesAreNotReconciledAfterACancelledScan() throws Exception {
        Path keep = write("keep.txt", "k");
        Path gone = write("gone.txt", "g");
        for (int i = 0; i < 20; i++) write("more/m" + i + ".txt", "m");
        scan();
        long before = rowCount();
        Files.delete(gone);

        CancellationToken token = new CancellationToken();
        AtomicInteger progressCalls = new AtomicInteger();
        Result r = scanner.scan(roots.byId(root.id()).orElseThrow(), null, token, p -> {
            if (!p.finished() && progressCalls.incrementAndGet() == 1) token.cancel();
        });
        assertFalse(r.completed());
        assertTrue(r.status().startsWith("Cancelled"), r.status());
        assertEquals(0, r.removed());
        assertTrue(items.byPath(gone).isPresent(), "an interrupted walk must never delete index rows");
        assertTrue(items.byPath(keep).isPresent());
        assertEquals(before, rowCount());
        assertTrue(roots.byId(root.id()).orElseThrow().lastScanStatus().startsWith("Cancelled"));

        Result full = scan();
        assertTrue(full.completed());
        assertEquals(1, full.removed());
        assertTrue(items.byPath(gone).isEmpty());
    }

    @Test
    void subtreeScanOnlyReconcilesItsOwnSubtree() throws Exception {
        Path a = write("a/one.txt", "1");
        Path b = write("b/two.txt", "2");
        scan();
        Files.delete(a);
        Files.delete(b);
        Result r = scanner.scan(roots.byId(root.id()).orElseThrow(), rootDir.resolve("a"), new CancellationToken(), p -> { });
        assertTrue(r.completed());
        assertTrue(items.byPath(a).isEmpty());
        assertTrue(items.byPath(b).isPresent(), "files outside the scanned subtree are untouched");
    }

    @Test
    void renamedFileKeepsItsIdentityRow() throws Exception {
        Path f = write("old-name.txt", "content");
        TestEnv.age(f, 10);
        scan();
        long id = items.byPath(f).orElseThrow().id();
        Path renamed = rootDir.resolve("new-name.txt");
        Files.move(f, renamed);
        Result r = scan();
        assertEquals(1, r.moved());
        assertEquals(id, items.byPath(renamed).orElseThrow().id());
        assertTrue(items.byPath(f).isEmpty());
        assertEquals(1, items.countFiles());
    }

    @Test
    void archivedOriginalRowsSurviveReconciliation() throws Exception {
        Path orig = write("report.txt", "original content");
        Path other = write("other.txt", "o");
        scan();
        IndexedItem it = items.byPath(orig).orElseThrow();
        items.setStatus(it.id(), OptStatus.ARCHIVED, null, null);
        Files.delete(orig);   // the original was replaced by an archive
        Files.delete(other);

        for (int i = 0; i < 2; i++) {
            Result r = scan();
            assertTrue(r.completed());
            IndexedItem kept = items.byPath(orig).orElseThrow(() -> new AssertionError("archived row was deleted"));
            assertEquals(OptStatus.ARCHIVED, kept.optStatus());
            assertFalse(kept.present(), "archived original is no longer present on disk");
            assertTrue(items.byPath(other).isEmpty(), "ordinary vanished files are removed");
        }
        // a subtree scan must keep it as well
        Result sub = scanner.scan(roots.byId(root.id()).orElseThrow(), rootDir, new CancellationToken(), p -> { });
        assertTrue(sub.completed());
        assertTrue(items.byPath(orig).isPresent());
        // markRemoved keeps archived rows too
        items.markRemoved(orig);
        assertTrue(items.byPath(orig).isPresent());
    }

    // ------------------------------------------------------------------ tool directories & links

    @Test
    void toolDirectoriesAreRecordedButNotDescended() throws Exception {
        write("proj/src/main.txt", "m");
        write("proj/node_modules/pkg/index.js", "x");
        write("proj/.git/config", "[core]");
        write("proj/.GIT2/keep.txt", "k");
        write("proj/__pycache__/a.pyc", "p");
        Result r = scan();
        assertTrue(r.completed());
        Path proj = rootDir.resolve("proj");
        assertTrue(items.byPath(proj.resolve("node_modules")).orElseThrow().directory(), "folder itself is recorded");
        assertTrue(items.byPath(proj.resolve(".git")).orElseThrow().directory());
        assertTrue(items.byPath(proj.resolve("__pycache__")).isPresent());
        assertTrue(items.byPath(proj.resolve("node_modules/pkg/index.js")).isEmpty(), "contents are not indexed");
        assertTrue(items.byPath(proj.resolve(".git/config")).isEmpty());
        assertTrue(items.byPath(proj.resolve("__pycache__/a.pyc")).isEmpty());
        assertTrue(items.byPath(proj.resolve("src/main.txt")).isPresent());
        assertTrue(items.byPath(proj.resolve(".GIT2/keep.txt")).isPresent(), "only exact tool folder names are skipped");
    }

    @Test
    void skippedDirectoryNamesAreCaseInsensitive() throws Exception {
        write("p/Node_Modules/x/y.js", "x");
        write("p/APPDATA/z.txt", "x");
        scan();
        assertTrue(items.byPath(rootDir.resolve("p/Node_Modules")).isPresent());
        assertTrue(items.byPath(rootDir.resolve("p/Node_Modules/x/y.js")).isEmpty());
        assertTrue(items.byPath(rootDir.resolve("p/APPDATA/z.txt")).isEmpty());
    }

    @Test
    void directoryLinksAreNeverFollowed() throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("Outside"));
        Files.writeString(outside.resolve("victim.txt"), "secret");
        Files.createDirectories(outside.resolve("deeper"));
        Files.writeString(outside.resolve("deeper/victim2.txt"), "secret");
        Path link = rootDir.resolve("link");
        Assumptions.assumeTrue(LinkSupport.createDirLink(link, outside), "cannot create a symlink or junction here");
        write("normal.txt", "n");

        Result r = scan();
        assertTrue(r.completed());
        assertTrue(items.byPath(link.resolve("victim.txt")).isEmpty(), "must not descend into the link");
        assertTrue(items.byPath(link.resolve("deeper/victim2.txt")).isEmpty());
        assertTrue(items.byPath(outside.resolve("victim.txt")).isEmpty());
        assertTrue(items.byPath(rootDir.resolve("normal.txt")).isPresent());
        IndexedItem linkItem = items.byPath(link).orElseThrow(() -> new AssertionError("the link itself is recorded"));
        assertTrue(linkItem.special(), "link is flagged as special");
        long indexedUnderLink = db.read(c -> Sql.scalarLong(c, "SELECT COUNT(*) FROM indexed_items WHERE path_key LIKE ? ESCAPE '\\'",
                Sql.likeEscape(PathUtil.key(link) + java.io.File.separator) + "%"));
        assertEquals(0, indexedUnderLink);
        assertEquals(1, items.countFiles() - (linkItem.directory() ? 0 : 1), "only normal.txt is a real file");
    }

    @Test
    void progressCallbackReceivesAFinalMessage() throws Exception {
        write("a.txt", "a");
        List<Progress> seen = new ArrayList<>();
        scanner.scan(root, null, new CancellationToken(), seen::add);
        assertFalse(seen.isEmpty());
        Progress last = seen.get(seen.size() - 1);
        assertTrue(last.finished());
        assertEquals(1, last.files());
    }
}
