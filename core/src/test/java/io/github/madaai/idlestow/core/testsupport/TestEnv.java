package io.github.madaai.idlestow.core.testsupport;

import io.github.madaai.idlestow.core.db.JobRepository;
import io.github.madaai.idlestow.core.engine.EngineConfig;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.ipc.OptimizationWorker;
import io.github.madaai.idlestow.core.ipc.PythonWorker;
import io.github.madaai.idlestow.core.ipc.WorkerClient;
import io.github.madaai.idlestow.core.ipc.WorkerLauncher;
import io.github.madaai.idlestow.core.jobs.FaultInjector;
import io.github.madaai.idlestow.core.platform.PlatformServices;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/** Helpers for engine-level tests. Everything happens inside JUnit temporary directories. */
public final class TestEnv {
    private static final AtomicLong UNIQUE = new AtomicLong();

    private TestEnv() { }

    public static OptimizationWorker worker() {
        WorkerLauncher l = WorkerLauncher.locate().orElseThrow(() ->
                new IllegalStateException("Python worker not found (idlestow.python.dir)"));
        return new PythonWorker(new WorkerClient(l));
    }

    public static IdleStowEngine engine(Path dataDir, PlatformServices platform, OptimizationWorker worker,
                                        FaultInjector faults) throws Exception {
        IdleStowEngine e = new IdleStowEngine(new EngineConfig(dataDir, platform, worker, Clock.systemUTC(), false,
                faults == null ? FaultInjector.NONE : faults));
        e.start();
        return e;
    }

    /** Settings suitable for deterministic tests: no observation period, small minimums. */
    public static void testSettings(IdleStowEngine e) throws Exception {
        var s = e.settings().with("policy.observationDays", "0").with("opt.minSavingsBytes", "1024")
                .with("opt.minFileSize", "1024").with("perf.minFreeReserveBytes", Long.toString(256L * 1024 * 1024))
                .with("onboarding.completed", "true");
        e.settingsStore().save(s);
    }

    /** Writes compressible text of {@code size} bytes and sets all timestamps to {@code daysAgo}. */
    public static Path textFile(Path p, int size, int daysAgo) throws IOException {
        Files.createDirectories(p.getParent());
        StringBuilder sb = new StringBuilder(size + 100);
        int i = 0;
        while (sb.length() < size) sb.append("Lecture notes line ").append(i++).append(": the quick brown fox.\n");
        Files.writeString(p, sb.substring(0, size), StandardCharsets.UTF_8);
        age(p, daysAgo);
        return p;
    }

    public static Path randomFile(Path p, int size, int daysAgo) throws IOException {
        Files.createDirectories(p.getParent());
        byte[] b = new byte[size];
        new Random(42 + size).nextBytes(b);
        Files.write(p, b);
        age(p, daysAgo);
        return p;
    }

    /** Sets creation/modification/access times; creation times are made unique (they back FakePlatform identity). */
    public static void age(Path p, int daysAgo) throws IOException {
        Instant t = Instant.now().minus(Duration.ofDays(daysAgo));
        FileTime ft = FileTime.from(t);
        FileTime created = FileTime.from(t.minusMillis(UNIQUE.incrementAndGet() * 7));
        Files.setAttribute(p, "basic:creationTime", created);
        Files.setLastModifiedTime(p, ft);
        Files.setAttribute(p, "basic:lastAccessTime", ft);
    }

    public static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(Files.readAllBytes(p)));
    }

    /** Waits (bounded) for a job to reach a terminal or recovery state. */
    public static JobRepository.Job awaitJob(IdleStowEngine e, long jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            JobRepository.Job j = e.jobs().job(jobId).orElseThrow();
            if (j.state().terminal() || j.state() == io.github.madaai.idlestow.core.model.Enums.JobState.RECOVERY_REQUIRED) {
                if (!e.jobManager().busy()) return j;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Job " + jobId + " did not finish in time");
    }
}
