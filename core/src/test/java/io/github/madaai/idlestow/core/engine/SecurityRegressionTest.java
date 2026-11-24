package io.github.madaai.idlestow.core.engine;

import io.github.madaai.idlestow.core.db.JobRepository;
import io.github.madaai.idlestow.core.db.Queries;
import io.github.madaai.idlestow.core.db.RecoveryRepository;
import io.github.madaai.idlestow.core.db.Sql;
import io.github.madaai.idlestow.core.ipc.OptimizationWorker;
import io.github.madaai.idlestow.core.jobs.SafeMove;
import io.github.madaai.idlestow.core.model.Enums.JobState;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.OptStatus;
import io.github.madaai.idlestow.core.model.Enums.Origin;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.testsupport.FakePlatform;
import io.github.madaai.idlestow.core.testsupport.TestEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for findings of the SEC-1 security / data-integrity review. */
class SecurityRegressionTest {

    static OptimizationWorker worker;
    @TempDir Path tmp;
    FakePlatform platform;
    IdleStowEngine engine;
    Path docs;

    @BeforeAll
    static void start() {
        worker = TestEnv.worker();
    }

    @AfterAll
    static void stop() {
        worker.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        platform = new FakePlatform();
        docs = tmp.resolve("docs");
        engine = TestEnv.engine(tmp.resolve("data"), platform, EngineScenariosTestAccess.nonClosing(worker), null);
        TestEnv.testSettings(engine);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    private IndexedItem item(Path p) throws Exception {
        return engine.queries().list(p.getParent(), null, Queries.Filter.ALL, Queries.SortField.NAME, true, 0, 100)
                .stream().map(Queries.Row::item).filter(i -> i.path().equals(p.toAbsolutePath().normalize()))
                .findFirst().orElseThrow();
    }

    private MonitoredRoot addAndScan() throws Exception {
        MonitoredRoot r = engine.addRoot(docs);
        engine.scan(r.id()).get();
        return r;
    }

    /** SEC-1 #1: renames must never replace an existing file. */
    @Test
    void safeMoveNeverOverwrites() throws Exception {
        Path a = Files.writeString(tmp.resolve("a.txt"), "new");
        Path b = Files.writeString(tmp.resolve("b.txt"), "user data");
        assertThrows(FileAlreadyExistsException.class, () -> SafeMove.renameNoReplace(a, b));
        assertEquals("user data", Files.readString(b));
        assertEquals("new", Files.readString(a));
        Path c = tmp.resolve("c.txt");
        SafeMove.renameNoReplace(a, c);
        assertEquals("new", Files.readString(c));
        assertTrue(Files.notExists(a));
    }

    /** SEC-1 #2: an edited retained original is never deleted by a restore. */
    @Test
    void restoreKeepsModifiedRetainedFile() throws Exception {
        Path f = TestEnv.textFile(docs.resolve("notes.txt"), 400_000, 100);
        String sha = TestEnv.sha256(f);
        addAndScan();
        IndexedItem it = item(f);
        long job = engine.execute(engine.preview(List.of(it.id()), Method.ZIP_REPLACE), List.of(it.id()),
                Origin.MANUAL, true);
        assertEquals(JobState.COMPLETED, TestEnv.awaitJob(engine, job).state());
        RecoveryRepository.Record rec = engine.recoveryRecords().currentForItem(it.id()).orElseThrow();
        Path retained = Path.of(rec.retainedOriginalPath());
        Files.writeString(retained, "the user edited the retained copy");

        long rj = engine.restore(rec.id(), null);
        JobRepository.Job r = TestEnv.awaitJob(engine, rj);
        assertEquals(JobState.RESTORED, r.state(), r.message());
        assertEquals(sha, TestEnv.sha256(f), "restored from the verified archive");
        assertEquals("the user edited the retained copy", Files.readString(retained), "edited copy kept");
    }

    /** SEC-1 #3: autonomous jobs re-assess each file right before acting. */
    @Test
    void autonomousPassSkipsFileUsedAfterAnalysis() throws Exception {
        Path f = TestEnv.textFile(docs.resolve("old.txt"), 400_000, 120);
        MonitoredRoot r = addAndScan();
        engine.settingsStore().update("bg.enabled", "true");
        engine.settingsStore().update("bg.maxCpuPercent", "100");
        engine.setRootAutonomy(r.id(), true);
        engine.reanalyze().get();
        engine.confirmAutonomy(engine.currentAutonomyPolicy());
        // the user reads the file after the analysis (reliable last-access tracking on the fake volume)
        // (outside the few-second window of IdleStow's own estimation read, which is deliberately ignored)
        Files.setAttribute(f, "basic:lastAccessTime", FileTime.from(Instant.now().plusSeconds(30)));
        long job = engine.runAutonomousPass().orElseThrow(() -> new AssertionError(engine.autonomyMessage()));
        JobRepository.Job j = TestEnv.awaitJob(engine, job);
        assertTrue(j.message().contains("no longer inactive"), j.message());
        assertEquals(OptStatus.NONE, engine.item(item(f).id()).orElseThrow().optStatus());
        assertTrue(platform.compressed.isEmpty());
    }

    /** SEC-1 #4: read-only mode cannot delete compressed copies. */
    @Test
    void readOnlyCannotDeleteCompressedCopy() throws Exception {
        Path f = TestEnv.textFile(docs.resolve("copy.txt"), 400_000, 100);
        addAndScan();
        IndexedItem it = item(f);
        long job = engine.execute(engine.preview(List.of(it.id()), Method.ZIP_COPY), List.of(it.id()),
                Origin.MANUAL, false);
        assertEquals(JobState.COMPLETED, TestEnv.awaitJob(engine, job).state());
        long recId = engine.recoveryRecords().currentForItem(it.id()).orElseThrow().id();
        Path archive = f.resolveSibling("copy.txt.idlestow.zip");
        engine.close();
        engine = new IdleStowEngine(new EngineConfig(tmp.resolve("data"), platform,
                EngineScenariosTestAccess.nonClosing(worker), java.time.Clock.systemUTC(), true,
                io.github.madaai.idlestow.core.jobs.FaultInjector.NONE));
        engine.start();
        assertTrue(engine.deleteCompressedCopy(recId).contains("read-only"));
        assertTrue(Files.exists(archive));
    }

    /** SEC-1 #6: a compressed copy is not deleted when the original has changed since. */
    @Test
    void copyKeptWhenOriginalChanged() throws Exception {
        Path f = TestEnv.textFile(docs.resolve("v1.txt"), 400_000, 100);
        addAndScan();
        IndexedItem it = item(f);
        long job = engine.execute(engine.preview(List.of(it.id()), Method.ZIP_COPY), List.of(it.id()),
                Origin.MANUAL, false);
        assertEquals(JobState.COMPLETED, TestEnv.awaitJob(engine, job).state());
        long recId = engine.recoveryRecords().currentForItem(it.id()).orElseThrow().id();
        Files.writeString(f, "a newer version");
        String msg = engine.deleteCompressedCopy(recId);
        assertTrue(msg.contains("kept"), msg);
        assertTrue(Files.exists(f.resolveSibling("v1.txt.idlestow.zip")));
    }

    /** SEC-1 #7: execution only covers the exact snapshot the user reviewed. */
    @Test
    void stalePreviewIsRejected() throws Exception {
        Path f = TestEnv.textFile(docs.resolve("stale.txt"), 400_000, 100);
        MonitoredRoot r = addAndScan();
        IndexedItem it = item(f);
        PlanPreview p = engine.preview(List.of(it.id()), Method.NATIVE);
        Files.writeString(f, "changed after review", java.nio.file.StandardOpenOption.APPEND);
        engine.scan(r.id()).get();
        IOException ex = assertThrows(IOException.class, () -> engine.execute(p, List.of(it.id()), Origin.MANUAL, false));
        assertTrue(ex.getMessage().contains("changed since you reviewed"), ex.getMessage());
        assertTrue(platform.compressed.isEmpty());
    }

    /** SEC-1 #5: a tampered recovery record cannot point deletions at arbitrary files. */
    @Test
    void tamperedRecoveryRecordIsRefused() throws Exception {
        Path f = TestEnv.textFile(docs.resolve("t.txt"), 400_000, 100);
        Path victim = Files.writeString(docs.resolve("victim.txt"), "must survive");
        addAndScan();
        IndexedItem it = item(f);
        long job = engine.execute(engine.preview(List.of(it.id()), Method.ZIP_REPLACE), List.of(it.id()),
                Origin.MANUAL, true);
        assertEquals(JobState.COMPLETED, TestEnv.awaitJob(engine, job).state());
        RecoveryRepository.Record rec = engine.recoveryRecords().currentForItem(it.id()).orElseThrow();
        engine.database().tx(c -> Sql.update(c, "UPDATE recovery_records SET retained_original_path=? WHERE id=?",
                victim.toString(), rec.id()));
        long pj = engine.reclaimRetained(List.of(rec.id()));
        JobRepository.Job p = TestEnv.awaitJob(engine, pj);
        assertEquals(JobState.FAILED, p.state());
        assertEquals("must survive", Files.readString(victim));
    }

    /** SEC-1 #12: roots are stored canonically. */
    @Test
    void rootsAreCanonicalized() throws Exception {
        Files.createDirectories(docs);
        MonitoredRoot r = engine.addRoot(docs.resolve("..").resolve("docs"));
        assertEquals(docs.toRealPath(), r.path());
    }
}
