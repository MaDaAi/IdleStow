package io.github.madaai.idlestow.core.engine;

import io.github.madaai.idlestow.core.db.CandidateRepository;
import io.github.madaai.idlestow.core.db.JobRepository;
import io.github.madaai.idlestow.core.db.Queries;
import io.github.madaai.idlestow.core.db.RecoveryRepository;
import io.github.madaai.idlestow.core.ipc.OptimizationWorker;
import io.github.madaai.idlestow.core.model.Enums.ActivityClass;
import io.github.madaai.idlestow.core.model.Enums.AuthorizationMode;
import io.github.madaai.idlestow.core.model.Enums.CandidateStatus;
import io.github.madaai.idlestow.core.model.Enums.JobState;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.OptStatus;
import io.github.madaai.idlestow.core.model.Enums.Origin;
import io.github.madaai.idlestow.core.model.Enums.RecoveryKind;
import io.github.madaai.idlestow.core.model.Enums.RecoveryStatus;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.platform.VolumeInfo;
import io.github.madaai.idlestow.core.testsupport.FakePlatform;
import io.github.madaai.idlestow.core.testsupport.TestEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release acceptance scenarios (A–K) at engine level, using the deterministic {@link FakePlatform}
 * for native compression and the REAL Python worker for hashing, estimation and archives.
 */
class EngineScenariosTest {

    static OptimizationWorker worker;

    @TempDir Path tmp;
    Path data;
    Path docs;
    FakePlatform platform;
    IdleStowEngine engine;

    @BeforeAll
    static void startWorker() {
        worker = TestEnv.worker();
    }

    @AfterAll
    static void stopWorker() {
        worker.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        data = tmp.resolve("data");
        docs = tmp.resolve("Documents");
        platform = new FakePlatform();
        engine = TestEnv.engine(data, platform, new NonClosingWorker(worker), null);
        TestEnv.testSettings(engine);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    /** The scenario from the specification (section 4). */
    private Map<String, Path> university() throws Exception {
        Path u = docs.resolve("University");
        return Map.of(
                "current", TestEnv.textFile(u.resolve("CurrentAssignment.docx"), 300_000, 0),
                "project", TestEnv.textFile(u.resolve("ProgrammingProject.java"), 300_000, 1),
                "old", TestEnv.textFile(u.resolve("OldLectureNotes.txt"), 400_000, 45),
                "a1", TestEnv.textFile(u.resolve("PreviousSemester/Assignment1.txt"), 300_000, 90),
                "a2", TestEnv.textFile(u.resolve("PreviousSemester/Assignment2.txt"), 300_000, 90),
                "notes", TestEnv.textFile(u.resolve("PreviousSemester/LectureNotes.txt"), 500_000, 90),
                "important", TestEnv.textFile(u.resolve("PersonalDocuments/ImportantDocument.pdf"), 300_000, 200));
    }

    private MonitoredRoot addAndScan() throws Exception {
        MonitoredRoot r = engine.addRoot(docs);
        engine.scan(r.id()).get();
        return r;
    }

    private IndexedItem item(Path p) throws Exception {
        return engine.queries().list(p.getParent(), null, Queries.Filter.ALL, Queries.SortField.NAME, true, 0, 1000)
                .stream().map(Queries.Row::item).filter(i -> i.path().equals(p.toAbsolutePath().normalize()))
                .findFirst().orElseThrow(() -> new AssertionError("not indexed: " + p));
    }

    private ActivityClass cls(Path p) throws Exception {
        return engine.classification(item(p).id()).orElseThrow().classification();
    }

    // ------------------------------------------------------------------ A + B: first scan, mixed activity

    @Test
    void scenarioA_B_initialScanIsReadOnlyAndMixedFolderRecommendsOnlyInactiveFiles() throws Exception {
        Map<String, Path> f = university();
        engine.pin(f.get("important"));
        // Hashing reads the files, which (correctly) counts as access on NTFS; restore the access time afterwards
        // so the test does not itself make the files "active".
        Map<Path, String> before = f.values().stream().collect(Collectors.toMap(p -> p, p -> {
            try {
                var atime = Files.getAttribute(p, "basic:lastAccessTime");
                String v = TestEnv.sha256(p) + Files.getLastModifiedTime(p);
                Files.setAttribute(p, "basic:lastAccessTime", atime);
                return v;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
        addAndScan();
        engine.reanalyze().get();

        // A: nothing modified by indexing
        for (var e : before.entrySet()) {
            assertEquals(e.getValue(), TestEnv.sha256(e.getKey()) + Files.getLastModifiedTime(e.getKey()));
        }
        // B: per-file classification inside one folder
        assertEquals(ActivityClass.ACTIVE, cls(f.get("current")));
        assertEquals(ActivityClass.ACTIVE, cls(f.get("project")));
        assertEquals(ActivityClass.INACTIVE, cls(f.get("old")));
        assertEquals(ActivityClass.INACTIVE, cls(f.get("a1")));
        assertEquals(ActivityClass.PINNED, cls(f.get("important")));

        List<String> recommended = engine.recommendations(List.of(CandidateStatus.PENDING), null, null,
                CandidateRepository.Sort.PATH, 100).stream().map(CandidateRepository.Candidate::name).toList();
        assertTrue(recommended.containsAll(List.of("OldLectureNotes.txt", "Assignment1.txt", "Assignment2.txt",
                "LectureNotes.txt")), recommended.toString());
        assertFalse(recommended.contains("CurrentAssignment.docx"));
        assertFalse(recommended.contains("ProgrammingProject.java"));
        assertFalse(recommended.contains("ImportantDocument.pdf"));
        assertEquals(1, engine.queries().totals().roots());
    }

    @Test
    void folderSelectionPreviewListsUntouchedActiveAndPinnedDescendants() throws Exception {
        Map<String, Path> f = university();
        engine.pin(f.get("important"));
        addAndScan();
        engine.reanalyze().get();
        IndexedItem uni = item(docs.resolve("University"));
        PlanPreview p = engine.preview(List.of(uni.id()), Method.NATIVE);
        List<String> change = p.willChange().stream().map(i -> i.path().getFileName().toString()).toList();
        List<String> untouched = p.untouched().stream().map(i -> i.path().getFileName().toString()).toList();
        assertTrue(change.contains("OldLectureNotes.txt"));
        assertTrue(untouched.containsAll(List.of("CurrentAssignment.docx", "ProgrammingProject.java",
                "ImportantDocument.pdf")), untouched.toString());
    }

    // ------------------------------------------------------------------ C + D: manual native optimization and decompress

    @Test
    void scenarioC_D_manualNativeCompressionVerifiedAndReversible() throws Exception {
        Map<String, Path> f = university();
        addAndScan();
        Path target = f.get("notes");
        String sha = TestEnv.sha256(target);
        IndexedItem it = item(target);
        PlanPreview p = engine.preview(List.of(it.id()), Method.NATIVE);
        assertEquals(1, p.willChange().size(), p.untouched().toString());
        long job = engine.execute(p, List.of(it.id()), Origin.MANUAL, false);
        JobRepository.Job j = TestEnv.awaitJob(engine, job);
        assertEquals(JobState.COMPLETED, j.state(), j.message());

        IndexedItem after = engine.item(it.id()).orElseThrow();
        assertEquals(OptStatus.NATIVE_COMPRESSED, after.optStatus());
        assertEquals(sha, TestEnv.sha256(target), "content unchanged and path still valid");
        assertTrue(engine.queries().currentReclaimed() > 0, "measured savings recorded");
        var hist = engine.history().search(target.getFileName().toString(), "SUCCEEDED", false, 0, 10);
        assertEquals(1, hist.size());
        assertNotNull(hist.get(0).actualSavings());
        assertTrue(hist.get(0).actualSavings() > 0);
        RecoveryRepository.Record rec = engine.recoveryRecords().currentForItem(it.id()).orElseThrow();
        assertEquals(RecoveryKind.NATIVE, rec.kind());

        long dj = engine.decompress(List.of(it.id()));
        assertEquals(JobState.COMPLETED, TestEnv.awaitJob(engine, dj).state());
        assertEquals(OptStatus.NONE, engine.item(it.id()).orElseThrow().optStatus());
        assertEquals(sha, TestEnv.sha256(target));
        assertEquals(RecoveryStatus.RESTORED, engine.recoveryRecords().byId(rec.id()).orElseThrow().status());
    }

    @Test
    void fileChangedAfterIndexingIsLeftUntouched() throws Exception {
        Map<String, Path> f = university();
        addAndScan();
        Path target = f.get("a1");
        IndexedItem it = item(target);
        PlanPreview p = engine.preview(List.of(it.id()), Method.NATIVE);
        Files.writeString(target, "changed after the preview", java.nio.file.StandardOpenOption.APPEND);
        long job = engine.execute(p, List.of(it.id()), Origin.MANUAL, false);
        JobRepository.Job j = TestEnv.awaitJob(engine, job);
        assertEquals(JobState.FAILED, j.state());
        assertTrue(j.message().contains("changed"), j.message());
        assertTrue(platform.compressed.isEmpty());
    }

    @Test
    void lockedFileIsNotModified() throws Exception {
        Map<String, Path> f = university();
        addAndScan();
        Path target = f.get("a2");
        platform.locked.add(target.toAbsolutePath().normalize().toString());
        IndexedItem it = item(target);
        long job = engine.execute(engine.preview(List.of(it.id()), Method.NATIVE), List.of(it.id()), Origin.MANUAL, false);
        JobRepository.Job j = TestEnv.awaitJob(engine, job);
        assertEquals(JobState.FAILED, j.state());
        assertTrue(j.message().contains("open in another program"), j.message());
    }

    // ------------------------------------------------------------------ G: archive replacement + restore (real worker)

    @Test
    void scenarioG_archiveReplacementIsVerifiedRetainedAndRestorable() throws Exception {
        Map<String, Path> f = university();
        addAndScan();
        Path target = f.get("notes");
        String sha = TestEnv.sha256(target);
        IndexedItem it = item(target);
        PlanPreview p = engine.preview(List.of(it.id()), Method.ZIP_REPLACE);
        assertTrue(p.changesAccessibility());
        assertThrows(java.io.IOException.class, () -> engine.execute(p, List.of(it.id()), Origin.MANUAL, false),
                "replacement must require explicit confirmation");
        long job = engine.execute(p, List.of(it.id()), Origin.MANUAL, true);
        JobRepository.Job j = TestEnv.awaitJob(engine, job);
        assertEquals(JobState.COMPLETED, j.state(), j.message());

        Path archive = target.resolveSibling(target.getFileName() + ".idlestow.zip");
        assertTrue(Files.isRegularFile(archive));
        assertFalse(Files.exists(target), "original moved to retention");
        RecoveryRepository.Record rec = engine.recoveryRecords().currentForItem(it.id()).orElseThrow();
        assertEquals(RecoveryKind.ARCHIVE_REPLACE, rec.kind());
        Path retained = Path.of(rec.retainedOriginalPath());
        assertEquals(sha, TestEnv.sha256(retained), "retained original intact");
        assertTrue(Files.isRegularFile(Path.of(rec.manifestPath())), "independent manifest written");
        assertEquals(0, engine.queries().currentReclaimed(), "nothing reported reclaimed while the original is retained");
        assertTrue(engine.queries().pendingAfterRetention() > 0);

        // verify integrity, then restore in place
        long vj = engine.verifyRecovery(List.of(rec.id()));
        assertEquals(JobState.COMPLETED, TestEnv.awaitJob(engine, vj).state());
        long rj = engine.restore(rec.id(), null);
        JobRepository.Job r = TestEnv.awaitJob(engine, rj);
        assertEquals(JobState.RESTORED, r.state(), r.message());
        assertEquals(sha, TestEnv.sha256(target));
        assertFalse(Files.exists(archive));
        assertEquals(RecoveryStatus.RESTORED, engine.recoveryRecords().byId(rec.id()).orElseThrow().status());
    }

    @Test
    void reclaimAfterRetentionRequiresVerifiedArchiveAndRestoreUsesArchive() throws Exception {
        Map<String, Path> f = university();
        addAndScan();
        Path target = f.get("a1");
        String sha = TestEnv.sha256(target);
        IndexedItem it = item(target);
        long job = engine.execute(engine.preview(List.of(it.id()), Method.ZIP_REPLACE), List.of(it.id()),
                Origin.MANUAL, true);
        assertEquals(JobState.COMPLETED, TestEnv.awaitJob(engine, job).state());
        RecoveryRepository.Record rec = engine.recoveryRecords().currentForItem(it.id()).orElseThrow();

        long pj = engine.reclaimRetained(List.of(rec.id()));
        assertEquals(JobState.COMPLETED, TestEnv.awaitJob(engine, pj).state());
        assertFalse(Files.exists(Path.of(rec.retainedOriginalPath())));
        assertTrue(engine.queries().currentReclaimed() > 0, "space reclaimed only after purge");

        // restore now comes from the archive
        RecoveryRepository.Record purged = engine.recoveryRecords().byId(rec.id()).orElseThrow();
        assertEquals(RecoveryStatus.ORIGINAL_PURGED, purged.status());
        long rj = engine.restore(rec.id(), null);
        assertEquals(JobState.RESTORED, TestEnv.awaitJob(engine, rj).state());
        assertEquals(sha, TestEnv.sha256(target));
    }

    @Test
    void damagedArchiveIsNeverReportedAsRecoverable() throws Exception {
        Map<String, Path> f = university();
        addAndScan();
        Path target = f.get("a2");
        IndexedItem it = item(target);
        long job = engine.execute(engine.preview(List.of(it.id()), Method.ZIP_REPLACE), List.of(it.id()),
                Origin.MANUAL, true);
        assertEquals(JobState.COMPLETED, TestEnv.awaitJob(engine, job).state());
        RecoveryRepository.Record rec = engine.recoveryRecords().currentForItem(it.id()).orElseThrow();
        // corrupt the archive and remove the retained original (simulates lost recovery copy)
        Path archive = Path.of(rec.archivePath());
        byte[] b = Files.readAllBytes(archive);
        for (int i = 40; i < Math.min(b.length - 100, 400); i++) b[i] ^= 0x5A;
        Files.write(archive, b);

        long pj = engine.reclaimRetained(List.of(rec.id()));
        JobRepository.Job pjob = TestEnv.awaitJob(engine, pj);
        assertEquals(JobState.FAILED, pjob.state());
        assertTrue(Files.exists(Path.of(rec.retainedOriginalPath())), "retained original must be kept");
        assertEquals(RecoveryStatus.DAMAGED, engine.recoveryRecords().byId(rec.id()).orElseThrow().status());
    }

    @Test
    void restoreNeverOverwritesExistingFileAndSupportsAlternateLocation() throws Exception {
        Map<String, Path> f = university();
        addAndScan();
        Path target = f.get("old");
        String sha = TestEnv.sha256(target);
        IndexedItem it = item(target);
        long job = engine.execute(engine.preview(List.of(it.id()), Method.ZIP_REPLACE), List.of(it.id()),
                Origin.MANUAL, true);
        assertEquals(JobState.COMPLETED, TestEnv.awaitJob(engine, job).state());
        RecoveryRepository.Record rec = engine.recoveryRecords().currentForItem(it.id()).orElseThrow();

        Files.writeString(target, "a new file the user created meanwhile");
        long rj = engine.restore(rec.id(), null);
        JobRepository.Job r = TestEnv.awaitJob(engine, rj);
        assertEquals(JobState.FAILED, r.state());
        assertTrue(r.message().contains("will not be overwritten"), r.message());
        assertEquals("a new file the user created meanwhile", Files.readString(target));

        Path alt = tmp.resolve("restored");
        Files.createDirectories(alt);
        long rj2 = engine.restore(rec.id(), alt.resolve("OldLectureNotes (restored).txt"));
        assertEquals(JobState.RESTORED, TestEnv.awaitJob(engine, rj2).state());
        assertEquals(sha, TestEnv.sha256(alt.resolve("OldLectureNotes (restored).txt")));
    }

    @Test
    void archiveCopyIsNonDestructiveAndNotCountedAsReclaimed() throws Exception {
        Map<String, Path> f = university();
        addAndScan();
        Path target = f.get("notes");
        String sha = TestEnv.sha256(target);
        IndexedItem it = item(target);
        long job = engine.execute(engine.preview(List.of(it.id()), Method.ZIP_COPY), List.of(it.id()),
                Origin.MANUAL, false);
        assertEquals(JobState.COMPLETED, TestEnv.awaitJob(engine, job).state());
        assertEquals(sha, TestEnv.sha256(target));
        assertTrue(Files.exists(target.resolveSibling(target.getFileName() + ".idlestow.zip")));
        assertEquals(0, engine.queries().currentReclaimed());
        assertEquals(OptStatus.ARCHIVE_COPY, engine.item(it.id()).orElseThrow().optStatus());
    }

    @Test
    void archiveCollisionIsRefused() throws Exception {
        Map<String, Path> f = university();
        addAndScan();
        Path target = f.get("a1");
        Path existing = target.resolveSibling(target.getFileName() + ".idlestow.zip");
        Files.writeString(existing, "user data that happens to have this name");
        IndexedItem it = item(target);
        long job = engine.execute(engine.preview(List.of(it.id()), Method.ZIP_COPY), List.of(it.id()),
                Origin.MANUAL, false);
        JobRepository.Job j = TestEnv.awaitJob(engine, job);
        assertEquals(JobState.FAILED, j.state());
        assertEquals("user data that happens to have this name", Files.readString(existing));
    }

    // ------------------------------------------------------------------ E + F: autonomy and revocation

    @Test
    void scenarioE_F_autonomyOnlyWithinConfirmedPolicyAndStopsWhenRevoked() throws Exception {
        Map<String, Path> f = university();
        Path outside = TestEnv.textFile(tmp.resolve("Outside/old.txt"), 300_000, 400);
        Path excluded = TestEnv.textFile(docs.resolve("University/PreviousSemester/excluded.txt"), 300_000, 120);
        engine.rules().addExclusion("PATH", excluded.toString());
        engine.rulesChanged();
        MonitoredRoot r = addAndScan();
        engine.settingsStore().update("bg.enabled", "true");
        engine.settingsStore().update("bg.maxCpuPercent", "100");

        // Not active until mode + confirmation
        assertFalse(engine.autonomyActive());
        assertTrue(engine.runAutonomousPass().isEmpty());

        engine.setRootAutonomy(r.id(), true);
        engine.reanalyze().get();
        IdleStowEngine.AutonomyPolicy pol = engine.currentAutonomyPolicy();
        assertEquals(List.of("NATIVE"), pol.methods());
        engine.confirmAutonomy(pol);
        assertTrue(engine.autonomyActive());

        long job = engine.runAutonomousPass().orElseThrow(() -> new AssertionError(engine.autonomyMessage()));
        assertEquals(JobState.COMPLETED, TestEnv.awaitJob(engine, job).state());
        assertEquals(OptStatus.NATIVE_COMPRESSED, item(f.get("notes")).optStatus());
        assertEquals(OptStatus.NONE, item(f.get("current")).optStatus(), "active file untouched");
        assertEquals(OptStatus.NONE, item(excluded).optStatus(), "excluded file untouched");
        assertEquals(1, Files.size(outside) > 0 ? 1 : 0);
        assertTrue(engine.history().search("Outside", null, false, 0, 10).isEmpty(), "outside root untouched");

        // F: disabling autonomy stops new automatic work immediately
        engine.setMode(AuthorizationMode.ASK);
        assertFalse(engine.autonomyActive());
        assertNotNull(engine.autonomyGate());
        assertTrue(engine.runAutonomousPass().isEmpty());

        // changing the policy after confirmation invalidates it
        engine.setRootAutonomy(r.id(), true);
        engine.confirmAutonomy(engine.currentAutonomyPolicy());
        engine.settingsStore().update("policy.inactivityDays", "7");
        assertFalse(engine.autonomyActive(), "policy changed since confirmation");
    }

    @Test
    void revokedRootBlocksMutation() throws Exception {
        Map<String, Path> f = university();
        MonitoredRoot r = addAndScan();
        IndexedItem it = item(f.get("notes"));
        PlanPreview p = engine.preview(List.of(it.id()), Method.NATIVE);
        engine.revokeRoot(r.id());
        // Revocation is re-checked when the approved plan is executed (and again before every file mutation).
        java.io.IOException ex = assertThrows(java.io.IOException.class,
                () -> engine.execute(p, List.of(it.id()), Origin.MANUAL, false));
        assertTrue(ex.getMessage().contains("revoked"), ex.getMessage());
        assertTrue(platform.compressed.isEmpty());
    }

    // ------------------------------------------------------------------ I, J, K

    @Test
    void scenarioI_insufficientStorageRefusesSafely() throws Exception {
        Map<String, Path> f = university();
        addAndScan();
        engine.settingsStore().update("perf.minFreeReserveBytes", Long.toString(1024L * 1024 * 1024 * 1024));
        Path target = f.get("notes");
        String sha = TestEnv.sha256(target);
        IndexedItem it = item(target);
        long job = engine.execute(engine.preview(List.of(it.id()), Method.ZIP_REPLACE), List.of(it.id()),
                Origin.MANUAL, true);
        JobRepository.Job j = TestEnv.awaitJob(engine, job);
        assertEquals(JobState.FAILED, j.state());
        assertTrue(j.message().contains("Insufficient free space"), j.message());
        assertEquals(sha, TestEnv.sha256(target));
    }

    @Test
    void scenarioJ_unsupportedFilesystemDoesNotOfferNativeCompression() throws Exception {
        platform.nativeSupported = false;
        Map<String, Path> f = university();
        addAndScan();
        IndexedItem it = item(f.get("notes"));
        PlanPreview p = engine.preview(List.of(it.id()), Method.NATIVE);
        assertTrue(p.willChange().isEmpty());
        assertTrue(p.untouched().get(0).reason().contains("not supported"), p.untouched().get(0).reason());
        // no native recommendations either
        assertTrue(engine.recommendations(List.of(CandidateStatus.PENDING), null, null,
                CandidateRepository.Sort.SAVINGS, 100).stream().noneMatch(c -> c.method() == Method.NATIVE));
    }

    @Test
    void scenarioK_unreliableAccessTrackingMeansUnknownAndNoAutomaticOptimization() throws Exception {
        platform.tracking = VolumeInfo.AccessTracking.DISABLED;
        Map<String, Path> f = university();
        MonitoredRoot r = addAndScan();
        assertEquals(ActivityClass.UNKNOWN, cls(f.get("a1")));
        assertEquals(ActivityClass.ACTIVE, cls(f.get("current")), "modification evidence still counts");
        assertTrue(engine.recommendations(List.of(CandidateStatus.PENDING), null, null,
                CandidateRepository.Sort.SAVINGS, 100).isEmpty());
        // archival designation is the explicit authorization that allows recommendations
        engine.setRootArchival(r.id(), true);
        engine.reanalyze().get();
        assertEquals(ActivityClass.INACTIVE, cls(f.get("a1")));
    }

    @Test
    void readOnlyModeRefusesMutations() throws Exception {
        engine.close();
        engine = new IdleStowEngine(new EngineConfig(data, platform, new NonClosingWorker(worker),
                java.time.Clock.systemUTC(), true, io.github.madaai.idlestow.core.jobs.FaultInjector.NONE));
        engine.start();
        TestEnv.testSettings(engine);
        Map<String, Path> f = university();
        addAndScan();
        IndexedItem it = item(f.get("notes"));
        PlanPreview p = engine.preview(List.of(it.id()), Method.NATIVE);
        assertThrows(java.io.IOException.class, () -> engine.execute(p, List.of(it.id()), Origin.MANUAL, false));
    }

    @Test
    void overlappingAndProtectedRootsAreRejected() throws Exception {
        Files.createDirectories(docs.resolve("sub"));
        engine.addRoot(docs);
        assertThrows(java.io.IOException.class, () -> engine.addRoot(docs.resolve("sub")));
        assertThrows(java.io.IOException.class, () -> engine.addRoot(tmp));
        Files.createDirectories(tmp.resolve("x/node_modules"));
        assertThrows(java.io.IOException.class, () -> engine.addRoot(tmp.resolve("x/node_modules")));
    }

    @Test
    void noBenefitIsRecordedForIncompressibleData() throws Exception {
        Path rnd = TestEnv.randomFile(docs.resolve("random.bin"), 2_000_000, 100);
        addAndScan();
        IndexedItem it = item(rnd);
        long job = engine.execute(engine.preview(List.of(it.id()), Method.ZIP_REPLACE), List.of(it.id()),
                Origin.MANUAL, true);
        TestEnv.awaitJob(engine, job);
        assertEquals(OptStatus.NO_BENEFIT, engine.item(it.id()).orElseThrow().optStatus());
        assertTrue(Files.exists(rnd), "original untouched");
        assertFalse(Files.exists(rnd.resolveSibling("random.bin.idlestow.zip")));
    }

    @Test
    void approvalsAreBoundToPlanContentsAndSingleUse() throws Exception {
        Map<String, Path> f = university();
        addAndScan();
        IndexedItem it = item(f.get("a1"));
        var repo = new CandidateRepository(engine.database(), java.time.Clock.systemUTC());
        long plan = repo.createPlan(Method.NATIVE, "MANUAL", "ASK", 1,
                List.of(new CandidateRepository.PlanItem(it.id(), it.path().toString(), it.logicalSize(),
                        it.modifiedMs(), it.fileKey())), 0);
        assertEquals("The plan has not been approved", repo.consumeApproval(plan));
        repo.approve(plan, java.time.Duration.ofHours(1));
        assertEquals("", repo.consumeApproval(plan));
        assertEquals("The approval was already used", repo.consumeApproval(plan));
        long plan2 = repo.createPlan(Method.NATIVE, "MANUAL", "ASK", 1,
                List.of(new CandidateRepository.PlanItem(it.id(), it.path().toString(), it.logicalSize(),
                        it.modifiedMs(), it.fileKey())), 0);
        repo.approve(plan2, java.time.Duration.ofMillis(-1));
        assertEquals("The approval has expired", repo.consumeApproval(plan2));
    }

    @Test
    void renamedFileKeepsItsIdentityAndHistory() throws Exception {
        Map<String, Path> f = university();
        MonitoredRoot r = addAndScan();
        IndexedItem before = item(f.get("a1"));
        Path renamed = f.get("a1").resolveSibling("Assignment1-renamed.txt");
        Files.move(f.get("a1"), renamed);
        engine.scan(r.id()).get();
        IndexedItem after = item(renamed);
        assertEquals(before.id(), after.id(), "move detected via stable file identity");
        assertNull(engine.queries().list(f.get("a1").getParent(), "Assignment1.txt", Queries.Filter.ALL,
                Queries.SortField.NAME, true, 0, 10).stream().filter(row -> row.item().name().equals("Assignment1.txt"))
                .findFirst().orElse(null));
    }

    /** Prevents individual engines from shutting down the shared worker process. */
    static final class NonClosingWorker implements OptimizationWorker {
        private final OptimizationWorker d;

        NonClosingWorker(OptimizationWorker d) {
            this.d = d;
        }

        @Override public java.util.Set<io.github.madaai.idlestow.core.model.Enums.ArchiveFormat> supportedFormats()
                throws io.github.madaai.idlestow.core.ipc.WorkerException { return d.supportedFormats(); }
        @Override public HashResult hash(Path f, io.github.madaai.idlestow.core.util.CancellationToken t,
                                         io.github.madaai.idlestow.core.ipc.WorkerClient.ProgressListener p)
                throws io.github.madaai.idlestow.core.ipc.WorkerException { return d.hash(f, t, p); }
        @Override public Estimate estimate(Path f, long m, io.github.madaai.idlestow.core.model.Enums.CompressionProfile p)
                throws io.github.madaai.idlestow.core.ipc.WorkerException { return d.estimate(f, m, p); }
        @Override public ArchiveResult createArchive(Path s, Path dst, io.github.madaai.idlestow.core.model.Enums.ArchiveFormat f,
                                                     io.github.madaai.idlestow.core.model.Enums.CompressionProfile p,
                                                     String e, String x, boolean l, Map<String, Object> m,
                                                     io.github.madaai.idlestow.core.util.CancellationToken t,
                                                     io.github.madaai.idlestow.core.ipc.WorkerClient.ProgressListener pl)
                throws io.github.madaai.idlestow.core.ipc.WorkerException {
            return d.createArchive(s, dst, f, p, e, x, l, m, t, pl);
        }
        @Override public VerifyResult verifyArchive(Path a, io.github.madaai.idlestow.core.model.Enums.ArchiveFormat f,
                                                    String e, String x, long s,
                                                    io.github.madaai.idlestow.core.util.CancellationToken t,
                                                    io.github.madaai.idlestow.core.ipc.WorkerClient.ProgressListener p)
                throws io.github.madaai.idlestow.core.ipc.WorkerException { return d.verifyArchive(a, f, e, x, s, t, p); }
        @Override public ExtractResult extractArchive(Path a, io.github.madaai.idlestow.core.model.Enums.ArchiveFormat f,
                                                      String e, Path dst, String x, long s,
                                                      io.github.madaai.idlestow.core.util.CancellationToken t,
                                                      io.github.madaai.idlestow.core.ipc.WorkerClient.ProgressListener p)
                throws io.github.madaai.idlestow.core.ipc.WorkerException { return d.extractArchive(a, f, e, dst, x, s, t, p); }
        @Override public SystemStatus systemStatus() throws io.github.madaai.idlestow.core.ipc.WorkerException { return d.systemStatus(); }
        @Override public String status() { return d.status(); }
        @Override public void close() { }
    }
}
