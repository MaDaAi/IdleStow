package io.github.madaai.idlestow.core.engine;

import io.github.madaai.idlestow.core.db.JobRepository;
import io.github.madaai.idlestow.core.db.Queries;
import io.github.madaai.idlestow.core.db.RecoveryRepository;
import io.github.madaai.idlestow.core.ipc.OptimizationWorker;
import io.github.madaai.idlestow.core.jobs.FaultInjector;
import io.github.madaai.idlestow.core.model.Enums.ArchiveFormat;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.Origin;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.platform.PlatformServices;
import io.github.madaai.idlestow.core.platform.VolumeInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * End-to-end self test used to verify an installed/packaged build: runs the real workflow on SYNTHETIC files
 * inside an explicitly provided empty directory, with a separate throw-away data directory. It never touches
 * the user's monitored folders or database. The directory is removed afterwards.
 */
public final class SelfTest {

    public record Result(boolean passed, List<String> lines) { }

    private SelfTest() { }

    public static Result run(Path workDir, PlatformServices platform, OptimizationWorker worker) {
        List<String> out = new ArrayList<>();
        boolean ok = true;
        try {
            if (Files.exists(workDir)) {
                try (Stream<Path> s = Files.list(workDir)) {
                    if (s.findAny().isPresent()) throw new IOException("Self-test directory must be empty: " + workDir);
                }
            }
            Path data = workDir.resolve("data");
            Path root = workDir.resolve("files");
            Path file = root.resolve("synthetic-notes.txt");
            Files.createDirectories(root);
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (sb.length() < 3_000_000) sb.append("IdleStow self-test line ").append(i++).append('\n');
            Files.writeString(file, sb, StandardCharsets.UTF_8);
            FileTime old = FileTime.from(Instant.now().minus(Duration.ofDays(120)));
            Files.setAttribute(file, "basic:creationTime", old);
            Files.setLastModifiedTime(file, old);
            Files.setAttribute(file, "basic:lastAccessTime", old);
            String sha = sha(file);
            Files.setAttribute(file, "basic:lastAccessTime", old);

            out.add("Worker: " + worker.status() + " formats=" + worker.supportedFormats());
            try (IdleStowEngine e = new IdleStowEngine(new EngineConfig(data, platform, new io.github.madaai.idlestow.core.ipc.SharedWorker(worker),
                    Clock.systemUTC(), false, FaultInjector.NONE))) {
                e.start();
                e.settingsStore().save(e.settings().with("policy.observationDays", "0")
                        .with("perf.minFreeReserveBytes", Long.toString(256L * 1024 * 1024)));
                MonitoredRoot r = e.addRoot(root);
                e.scan(r.id()).get();
                IndexedItem it = e.queries().list(root, null, Queries.Filter.ALL, Queries.SortField.NAME, true, 0, 10)
                        .stream().map(Queries.Row::item).filter(x -> !x.directory()).findFirst().orElseThrow();
                out.add("Indexed: " + it.path() + " (" + it.logicalSize() + " bytes), activity="
                        + e.classification(it.id()).map(a -> a.classification().name()).orElse("?"));

                VolumeInfo v = platform.fileInspector().volumeOf(root);
                if (platform.nativeCompression().supportedOn(v)) {
                    long job = e.execute(e.preview(List.of(it.id()), Method.NATIVE), List.of(it.id()), Origin.MANUAL, false);
                    JobRepository.Job j = await(e, job);
                    boolean good = j.state().name().equals("COMPLETED") && sha.equals(sha(file))
                            && platform.nativeCompression().isCompressed(file);
                    out.add("Native compression: " + j.state() + " - " + j.message() + " -> " + (good ? "PASS" : "FAIL"));
                    ok &= good;
                    long dj = e.decompress(List.of(it.id()));
                    JobRepository.Job d = await(e, dj);
                    boolean dgood = d.state().name().equals("COMPLETED") && sha.equals(sha(file))
                            && !platform.nativeCompression().isCompressed(file);
                    out.add("Native decompression: " + d.state() + " -> " + (dgood ? "PASS" : "FAIL"));
                    ok &= dgood;
                } else {
                    out.add("Native compression: not supported on this volume (" + v.nativeCompressionNote() + ") — SKIPPED");
                }
                e.scan(r.id()).get();
                it = e.item(it.id()).orElseThrow();
                for (ArchiveFormat f : worker.supportedFormats()) {
                    Method m = f == ArchiveFormat.ZIP ? Method.ZIP_REPLACE : Method.TZST_REPLACE;
                    long job = e.execute(e.preview(List.of(it.id()), m), List.of(it.id()), Origin.MANUAL, true);
                    JobRepository.Job j = await(e, job);
                    RecoveryRepository.Record rec = e.recoveryRecords().currentForItem(it.id()).orElse(null);
                    boolean archived = j.state().name().equals("COMPLETED") && rec != null && !Files.exists(file);
                    out.add("Archive replace (" + f.wire() + "): " + j.state() + " -> " + (archived ? "PASS" : "FAIL"));
                    ok &= archived;
                    if (rec != null) {
                        long rj = e.restore(rec.id(), null);
                        JobRepository.Job rr = await(e, rj);
                        boolean restored = rr.state().name().equals("RESTORED") && sha.equals(sha(file));
                        out.add("Restore (" + f.wire() + "): " + rr.state() + " -> " + (restored ? "PASS" : "FAIL"));
                        ok &= restored;
                    }
                    e.scan(r.id()).get();
                    it = e.queries().list(root, null, Queries.Filter.ALL, Queries.SortField.NAME, true, 0, 10)
                            .stream().map(Queries.Row::item).filter(x -> !x.directory()
                                    && x.name().equals("synthetic-notes.txt")).findFirst().orElseThrow();
                }
            }
        } catch (Exception ex) {
            out.add("ERROR: " + ex);
            ok = false;
        } finally {
            deleteTree(workDir);
        }
        out.add(ok ? "SELF-TEST PASSED" : "SELF-TEST FAILED");
        return new Result(ok, out);
    }

    private static JobRepository.Job await(IdleStowEngine e, long job) throws Exception {
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            JobRepository.Job j = e.jobs().job(job).orElseThrow();
            if ((j.state().terminal() || j.state().name().equals("RECOVERY_REQUIRED")) && !e.jobManager().busy()) return j;
            Thread.sleep(100);
        }
        throw new IOException("Job " + job + " timed out");
    }

    private static String sha(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Removes only the self-test's own directory tree. */
    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort cleanup of synthetic data
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
