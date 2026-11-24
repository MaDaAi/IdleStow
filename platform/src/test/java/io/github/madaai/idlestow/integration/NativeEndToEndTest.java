package io.github.madaai.idlestow.integration;

import io.github.madaai.idlestow.core.db.JobRepository;
import io.github.madaai.idlestow.core.db.Queries;
import io.github.madaai.idlestow.core.engine.EngineConfig;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.engine.PlanPreview;
import io.github.madaai.idlestow.core.ipc.PythonWorker;
import io.github.madaai.idlestow.core.ipc.WorkerClient;
import io.github.madaai.idlestow.core.ipc.WorkerLauncher;
import io.github.madaai.idlestow.core.jobs.FaultInjector;
import io.github.madaai.idlestow.core.model.Enums.ActivityClass;
import io.github.madaai.idlestow.core.model.Enums.JobState;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.OptStatus;
import io.github.madaai.idlestow.core.model.Enums.Origin;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.platform.NativeFileState;
import io.github.madaai.idlestow.core.platform.VolumeInfo;
import io.github.madaai.idlestow.platform.windows.WindowsPlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end on a REAL NTFS volume with the REAL Windows adapter and the REAL Python worker:
 * index → classify → preview → native compression → verification → measured savings → decompression.
 * Only synthetic files inside a JUnit temp directory (redirected to target/) are touched.
 */
@Tag("native")
@EnabledOnOs(OS.WINDOWS)
class NativeEndToEndTest {

    @TempDir Path tmp;
    IdleStowEngine engine;
    WindowsPlatform platform;

    @BeforeEach
    void setUp() throws Exception {
        platform = new WindowsPlatform("IdleStowTest-unused");
        VolumeInfo v = platform.fileInspector().volumeOf(tmp);
        Assumptions.assumeTrue(v.nativeCompression(), "temp volume does not support NTFS compression: "
                + v.nativeCompressionNote());
        WorkerLauncher l = WorkerLauncher.locate().orElseThrow();
        engine = new IdleStowEngine(new EngineConfig(tmp.resolve("data"), platform,
                new PythonWorker(new WorkerClient(l)), Clock.systemUTC(), false, FaultInjector.NONE));
        engine.start();
        var s = engine.settings().with("policy.observationDays", "0").with("onboarding.completed", "true")
                .with("perf.minFreeReserveBytes", Long.toString(256L * 1024 * 1024));
        engine.settingsStore().save(s);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.close();
    }

    static Path text(Path p, int size, int daysAgo) throws Exception {
        Files.createDirectories(p.getParent());
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (sb.length() < size) sb.append("Semester notes, line ").append(i++).append(" — lorem ipsum dolor.\n");
        Files.writeString(p, sb.substring(0, size), StandardCharsets.UTF_8);
        FileTime t = FileTime.from(Instant.now().minus(Duration.ofDays(daysAgo)));
        Files.setAttribute(p, "basic:creationTime", t);
        Files.setLastModifiedTime(p, t);
        Files.setAttribute(p, "basic:lastAccessTime", t);
        return p;
    }

    static String sha(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    IndexedItem item(Path p) throws Exception {
        return engine.queries().list(p.getParent(), null, Queries.Filter.ALL, Queries.SortField.NAME, true, 0, 100)
                .stream().map(Queries.Row::item).filter(i -> i.path().equals(p.toAbsolutePath().normalize()))
                .findFirst().orElseThrow();
    }

    JobRepository.Job await(long job) throws Exception {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            JobRepository.Job j = engine.jobs().job(job).orElseThrow();
            if (j.state().terminal() && !engine.jobManager().busy()) return j;
            Thread.sleep(100);
        }
        throw new AssertionError("timeout");
    }

    @Test
    void mixedFolderNativeCompressionOnRealNtfs() throws Exception {
        Path root = tmp.resolve("Documents");
        Path dir = root.resolve("University");
        Path active = text(dir.resolve("CurrentAssignment.txt"), 2_000_000, 0);
        Path inactive = text(dir.resolve("PreviousSemester/LectureNotes.txt"), 4_000_000, 90);
        Path inactive2 = text(dir.resolve("OldLectureNotes.txt"), 3_000_000, 45);
        Map<Path, String> before = Map.of(active, sha(active), inactive, sha(inactive), inactive2, sha(inactive2));
        // restore access times disturbed by hashing in the test itself
        for (Path p : List.of(inactive, inactive2)) {
            Files.setAttribute(p, "basic:lastAccessTime", Files.getLastModifiedTime(p));
        }
        MonitoredRoot r = engine.addRoot(root);
        engine.scan(r.id()).get();
        assertEquals(ActivityClass.ACTIVE, engine.classification(item(active).id()).orElseThrow().classification());
        assertEquals(ActivityClass.INACTIVE, engine.classification(item(inactive).id()).orElseThrow().classification());

        // Select the whole folder: only the inactive files are planned
        PlanPreview p = engine.preview(List.of(item(dir).id()), Method.NATIVE);
        List<Path> planned = p.willChange().stream().map(PlanPreview.Item::path).toList();
        assertTrue(planned.contains(inactive.toAbsolutePath().normalize()));
        assertTrue(planned.contains(inactive2.toAbsolutePath().normalize()));
        assertFalse(planned.contains(active.toAbsolutePath().normalize()));

        long beforeAlloc = platform.fileInspector().inspect(inactive).allocatedSize();
        long job = engine.execute(p, p.willChange().stream().map(PlanPreview.Item::itemId).toList(), Origin.MANUAL, false);
        JobRepository.Job j = await(job);
        assertEquals(JobState.COMPLETED, j.state(), j.message());

        NativeFileState st = platform.fileInspector().inspect(inactive);
        assertTrue(st.compressed(), "NTFS compression attribute set");
        assertTrue(st.allocatedSize() < beforeAlloc / 2, "real allocation shrank: " + beforeAlloc + " -> " + st.allocatedSize());
        assertEquals(OptStatus.NATIVE_COMPRESSED, item(inactive).optStatus());
        for (var e : before.entrySet()) assertEquals(e.getValue(), sha(e.getKey()), "content identical: " + e.getKey());
        assertFalse(platform.fileInspector().inspect(active).compressed(), "active sibling untouched");
        assertFalse(platform.nativeCompression().isCompressed(dir), "folder compression inheritance unchanged");
        long reclaimed = engine.queries().currentReclaimed();
        assertTrue(reclaimed > 3_000_000, "measured reclaimed bytes: " + reclaimed);

        // IdleStow's own verification reads must not make the file look active after a rescan
        engine.scan(r.id()).get();
        assertEquals(ActivityClass.INACTIVE, engine.classification(item(inactive).id()).orElseThrow().classification(),
                engine.classification(item(inactive).id()).orElseThrow().explanation());

        // Decompress
        long dj = engine.decompress(List.of(item(inactive).id()));
        assertEquals(JobState.COMPLETED, await(dj).state());
        assertFalse(platform.fileInspector().inspect(inactive).compressed());
        assertEquals(before.get(inactive), sha(inactive));
    }
}
