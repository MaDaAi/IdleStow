package io.github.madaai.idlestow.core.jobs;

import io.github.madaai.idlestow.core.db.Queries;
import io.github.madaai.idlestow.core.db.RecoveryRepository;
import io.github.madaai.idlestow.core.engine.EngineScenariosTestAccess;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.ipc.OptimizationWorker;
import io.github.madaai.idlestow.core.model.Enums.JobState;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.OptStatus;
import io.github.madaai.idlestow.core.model.Enums.Origin;
import io.github.madaai.idlestow.core.model.Enums.RecoveryKind;
import io.github.madaai.idlestow.core.model.Enums.RecoveryStatus;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.testsupport.FakePlatform;
import io.github.madaai.idlestow.core.testsupport.TestEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure injection: the process "crashes" (an uncaught Error) at each significant step, then a new engine
 * starts on the same data directory and runs startup recovery. Invariant checked every time: the user's data
 * is never lost — a verified copy exists at the original path, in retention, or in a valid archive — and the
 * final state is either completed, rolled back, or clearly flagged for review.
 */
class CrashRecoveryTest {

    static OptimizationWorker worker;

    @TempDir Path tmp;

    @BeforeAll
    static void start() {
        worker = TestEnv.worker();
    }

    @AfterAll
    static void stop() {
        worker.close();
    }

    record Setup(FakePlatform platform, Path data, Path file, String sha, long itemId) { }

    private Setup crashDuring(Method method, String crashStep) throws Exception {
        FakePlatform platform = new FakePlatform();
        Path data = tmp.resolve("data");
        Path docs = tmp.resolve("docs");
        Path file = TestEnv.textFile(docs.resolve("Archive/report.txt"), 600_000, 120);
        String sha = TestEnv.sha256(file);
        TestEnv.age(file, 120);
        FaultInjector faults = step -> {
            if (step.equals(crashStep)) throw new FaultInjector.SimulatedCrash(step);
        };
        IdleStowEngine e = TestEnv.engine(data, platform, EngineScenariosTestAccess.nonClosing(worker), faults);
        TestEnv.testSettings(e);
        MonitoredRoot r = e.addRoot(docs);
        e.scan(r.id()).get();
        IndexedItem it = e.queries().list(file.getParent(), null, Queries.Filter.ALL, Queries.SortField.NAME, true, 0, 10)
                .get(0).item();
        e.execute(e.preview(List.of(it.id()), method), List.of(it.id()), Origin.MANUAL, method.replacesOriginal());
        long deadline = System.currentTimeMillis() + 30_000;
        Thread.sleep(200);
        while (e.jobManager().busy() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        e.close(); // the process dies; journal + files stay as they were at the crash point
        return new Setup(platform, data, file, sha, it.id());
    }

    private IdleStowEngine restart(Setup s) throws Exception {
        IdleStowEngine e = TestEnv.engine(s.data(), s.platform(), EngineScenariosTestAccess.nonClosing(worker), null);
        return e;
    }

    /** Data-safety invariant. */
    private void assertNoDataLoss(IdleStowEngine e, Setup s) throws Exception {
        if (Files.exists(s.file())) {
            assertEquals(s.sha(), TestEnv.sha256(s.file()), "content at original path intact");
            return;
        }
        RecoveryRepository.Record rec = e.recoveryRecords().currentForItem(s.itemId()).orElse(null);
        boolean safe = false;
        if (rec != null && rec.retainedOriginalPath() != null && Files.exists(Path.of(rec.retainedOriginalPath()))) {
            safe = s.sha().equals(TestEnv.sha256(Path.of(rec.retainedOriginalPath())));
        }
        if (!safe && rec != null && rec.archivePath() != null && Files.exists(Path.of(rec.archivePath()))) {
            // after an explicit, verified purge the archive is (by the user's choice) the only copy: it must verify
            safe = worker.verifyArchive(Path.of(rec.archivePath()),
                    io.github.madaai.idlestow.core.model.Enums.ArchiveFormat.fromWire(rec.archiveFormat()),
                    rec.entryName(), s.sha(), rec.size(), null, null).ok();
        }
        try (Stream<Path> st = Files.walk(s.data().getParent())) {
            if (!safe) {
                // search the retention store directly (record may be flagged for review)
                safe = st.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().equals("report.txt"))
                        .anyMatch(p -> {
                            try {
                                return s.sha().equals(TestEnv.sha256(p));
                            } catch (Exception ex) {
                                return false;
                            }
                        });
            }
        }
        assertTrue(safe, "a verified copy of the data must exist somewhere");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NATIVE_BEFORE_MUTATION", "NATIVE_AFTER_MUTATION", "NATIVE_BEFORE_COMMIT"})
    void nativeCompressionCrashes(String step) throws Exception {
        Setup s = crashDuring(Method.NATIVE, step);
        try (IdleStowEngine e = restart(s)) {
            var report = e.startupRecovery().orElseThrow();
            assertEquals(1, report.inspected(), report.notes().toString());
            assertNoDataLoss(e, s);
            IndexedItem it = e.item(s.itemId()).orElseThrow();
            if (step.equals("NATIVE_BEFORE_MUTATION")) {
                assertEquals(1, report.rolledBack());
                assertEquals(OptStatus.NONE, it.optStatus());
            } else {
                assertEquals(1, report.completed(), report.notes().toString());
                assertEquals(OptStatus.NATIVE_COMPRESSED, it.optStatus());
                assertEquals(RecoveryKind.NATIVE, e.recoveryRecords().currentForItem(s.itemId()).orElseThrow().kind());
            }
            // no job left in a running state
            assertTrue(e.jobs().jobs(10).stream().allMatch(j -> j.state().terminal()
                    || j.state() == JobState.RECOVERY_REQUIRED));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARCHIVE_BEFORE_TMP", "ARCHIVE_AFTER_TMP", "ARCHIVE_AFTER_VERIFY", "ARCHIVE_AFTER_PLACE",
            "ARCHIVE_AFTER_RETAIN"})
    void archiveReplacementCrashes(String step) throws Exception {
        Setup s = crashDuring(Method.ZIP_REPLACE, step);
        try (IdleStowEngine e = restart(s)) {
            var report = e.startupRecovery().orElseThrow();
            assertEquals(1, report.inspected(), report.notes().toString());
            assertEquals(0, report.review(), report.notes().toString());
            assertNoDataLoss(e, s);
            Path dir = s.file().getParent();
            try (Stream<Path> files = Files.list(dir)) {
                assertTrue(files.noneMatch(p -> p.getFileName().toString().contains(".idlestow-tmp-")),
                        "temporary output cleaned up");
            }
            IndexedItem it = e.item(s.itemId()).orElseThrow();
            switch (step) {
                case "ARCHIVE_AFTER_PLACE" -> {
                    assertTrue(Files.exists(s.file()), "original never moved");
                    assertEquals(OptStatus.ARCHIVE_COPY, it.optStatus());
                }
                case "ARCHIVE_AFTER_RETAIN" -> {
                    assertEquals(OptStatus.ARCHIVED, it.optStatus());
                    RecoveryRepository.Record rec = e.recoveryRecords().currentForItem(s.itemId()).orElseThrow();
                    assertEquals(RecoveryKind.ARCHIVE_REPLACE, rec.kind());
                    // and it is restorable
                    long job = e.restore(rec.id(), null);
                    assertEquals(JobState.RESTORED, TestEnv.awaitJob(e, job).state());
                    assertEquals(s.sha(), TestEnv.sha256(s.file()));
                }
                default -> {
                    assertTrue(Files.exists(s.file()));
                    assertEquals(OptStatus.NONE, it.optStatus());
                    assertFalse(Files.exists(s.file().resolveSibling("report.txt.idlestow.zip")));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESTORE_BEFORE_EXTRACT", "RESTORE_BEFORE_MOVE", "RESTORE_AFTER_PLACE", "PURGE_BEFORE_DELETE"})
    void restoreAndPurgeCrashes(String step) throws Exception {
        // first archive normally, then crash during the restore/purge
        Setup base = crashDuring(Method.ZIP_REPLACE, "NO_CRASH");
        FaultInjector faults = st -> {
            if (st.equals(step)) throw new FaultInjector.SimulatedCrash(st);
        };
        IdleStowEngine e1 = TestEnv.engine(base.data(), base.platform(), EngineScenariosTestAccess.nonClosing(worker), faults);
        RecoveryRepository.Record rec = e1.recoveryRecords().currentForItem(base.itemId()).orElseThrow();
        if (step.equals("RESTORE_BEFORE_EXTRACT")) {
            // force the archive path: remove the retained original first via a purge (verified)
            IdleStowEngine tmpE = e1;
            long pj = tmpE.reclaimRetained(List.of(rec.id()));
            TestEnv.awaitJob(tmpE, pj);
        }
        if (step.equals("PURGE_BEFORE_DELETE")) e1.reclaimRetained(List.of(rec.id()));
        else e1.restore(rec.id(), null);
        Thread.sleep(200);
        long deadline = System.currentTimeMillis() + 30_000;
        while (e1.jobManager().busy() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        e1.close();

        try (IdleStowEngine e = restart(base)) {
            var report = e.startupRecovery().orElseThrow();
            assertEquals(1, report.inspected(), report.notes().toString());
            assertNoDataLoss(e, base);
            RecoveryRepository.Record after = e.recoveryRecords().byId(rec.id()).orElseThrow();
            if (step.equals("RESTORE_AFTER_PLACE")) {
                assertEquals(RecoveryStatus.REVIEW, after.status(), "ambiguous → flagged, archive kept");
                assertTrue(Files.exists(Path.of(after.archivePath())));
            }
            if (step.equals("PURGE_BEFORE_DELETE")) {
                assertTrue(Files.exists(Path.of(after.retainedOriginalPath())), "retained original not lost");
            }
        }
    }

    @Test
    void cleanRunLeavesNothingToRecover() throws Exception {
        Setup s = crashDuring(Method.NATIVE, "NO_CRASH");
        try (IdleStowEngine e = restart(s)) {
            assertEquals(0, e.startupRecovery().orElseThrow().inspected());
            assertEquals(OptStatus.NATIVE_COMPRESSED, e.item(s.itemId()).orElseThrow().optStatus());
        }
    }
}
