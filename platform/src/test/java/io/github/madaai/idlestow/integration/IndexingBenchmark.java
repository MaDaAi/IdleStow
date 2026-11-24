package io.github.madaai.idlestow.integration;

import io.github.madaai.idlestow.core.db.Queries;
import io.github.madaai.idlestow.core.engine.EngineConfig;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.ipc.UnavailableWorker;
import io.github.madaai.idlestow.core.jobs.FaultInjector;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.platform.Platforms;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Reproducible indexing benchmark on synthetic data (excluded from normal test runs).
 *
 * <pre>
 * ./mvnw -pl platform test -Dtest=IndexingBenchmark -Dsurefire.excludedGroups= -Dbench.sizes=1000,10000,100000
 * </pre>
 * Results are written to {@code platform/target/benchmark-indexing.md}. Uses the real platform adapter;
 * the Python worker is intentionally not used (indexing and classification are metadata-only).
 */
@Tag("benchmark")
class IndexingBenchmark {

    @Test
    void run() throws Exception {
        String sizes = System.getProperty("bench.sizes", "1000,10000,100000");
        Path base = Path.of(System.getProperty("java.io.tmpdir")).resolve("idlestow-bench");
        deleteTree(base);
        List<String> rows = new ArrayList<>();
        rows.add("| Files | Dirs | Initial index + analysis (s) | Files/s | Unchanged rescan (s) | Explorer page query (ms) | Search query (ms) | DB size (MiB) | Heap used after (MiB) |");
        rows.add("|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
        for (String s : sizes.split(",")) {
            int n = Integer.parseInt(s.trim());
            Path root = base.resolve("data-" + n);
            int dirs = generate(root, n);
            Path dataDir = base.resolve("state-" + n);
            try (IdleStowEngine e = new IdleStowEngine(new EngineConfig(dataDir, Platforms.detect(),
                    new UnavailableWorker("benchmark: metadata only"), Clock.systemUTC(), true, FaultInjector.NONE))) {
                e.start();
                MonitoredRoot r = e.addRoot(root);
                long t0 = System.nanoTime();
                e.scan(r.id()).get();
                double initial = (System.nanoTime() - t0) / 1e9;
                long t1 = System.nanoTime();
                e.scan(r.id()).get();
                double rescan = (System.nanoTime() - t1) / 1e9;
                Path deep = root.resolve("d0");
                long t2 = System.nanoTime();
                e.queries().list(deep, null, Queries.Filter.ALL, Queries.SortField.SIZE, false, 0, 500);
                double pageMs = (System.nanoTime() - t2) / 1e6;
                long t3 = System.nanoTime();
                e.queries().list(root, "file-12", Queries.Filter.ALL, Queries.SortField.NAME, true, 0, 500);
                double searchMs = (System.nanoTime() - t3) / 1e6;
                long files = e.queries().totals().files();
                if (files != n) throw new AssertionError("indexed " + files + " of " + n);
                System.gc();
                long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                e.database().checkpoint();
                long dbSize = Files.size(dataDir.resolve("idlestow.db"));
                rows.add(String.format(Locale.ROOT, "| %d | %d | %.2f | %.0f | %.2f | %.1f | %.1f | %.1f | %.0f |", n, dirs,
                        initial, n / initial, rescan, pageMs, searchMs, dbSize / 1048576.0, heap / 1048576.0));
            }
            deleteTree(root);
            deleteTree(dataDir);
        }
        String env = "Hardware: " + System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown") + ", "
                + Runtime.getRuntime().availableProcessors() + " logical CPUs, max heap "
                + Runtime.getRuntime().maxMemory() / 1048576 + " MiB\n\nOS: " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + "\n\nJava: " + System.getProperty("java.version") + " ("
                + System.getProperty("java.vendor") + ")\n\nDataset: synthetic, 50 files per folder, 3 folder levels, "
                + "sizes 0–64 KiB, metadata-only scan (no content reads), platform adapter: " + Platforms.detect().name();
        String report = "# Indexing benchmark\n\n" + env + "\n\n" + String.join("\n", rows) + "\n";
        Files.writeString(Path.of("target", "benchmark-indexing.md"), report, StandardCharsets.UTF_8);
        System.out.println(report);
    }

    /** Creates {@code n} small files spread over nested folders. Returns the number of folders. */
    static int generate(Path root, int n) throws Exception {
        Random rnd = new Random(7);
        byte[] buf = new byte[65536];
        rnd.nextBytes(buf);
        int perDir = 50;
        int dirsCreated = 0;
        for (int i = 0; i < n; i++) {
            int d = i / perDir;
            Path dir = root.resolve("d" + (d % 20)).resolve("e" + (d / 20 % 50)).resolve("f" + d);
            if (i % perDir == 0) {
                Files.createDirectories(dir);
                dirsCreated++;
            }
            int size = rnd.nextInt(65536);
            Files.write(dir.resolve("file-" + i + ".txt"), java.util.Arrays.copyOf(buf, size));
        }
        return dirsCreated;
    }

    static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }
}
