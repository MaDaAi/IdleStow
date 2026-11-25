package io.github.madaai.idlestow.core.db;

import io.github.madaai.idlestow.core.db.CandidateRepository.NewCandidate;
import io.github.madaai.idlestow.core.index.ScannedEntry;
import io.github.madaai.idlestow.core.ipc.UnavailableWorker;
import io.github.madaai.idlestow.core.model.Enums.CandidateStatus;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.policy.PolicyEngine;
import io.github.madaai.idlestow.core.policy.ProtectedRules;
import io.github.madaai.idlestow.core.recommend.AnalysisService;
import io.github.madaai.idlestow.core.settings.Settings;
import io.github.madaai.idlestow.core.testsupport.FakePlatform;
import io.github.madaai.idlestow.core.util.CancellationToken;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Database/analysis micro-benchmark on purely synthetic index rows (no filesystem walk, no real files).
 *
 * <pre>
 * ./mvnw -pl core test -Dtest=DbPerfBenchmark -Dsurefire.excludedGroups= -Dbench.n=100000
 * </pre>
 * Writes {@code core/target/benchmark-db.md}.
 */
@Tag("benchmark")
class DbPerfBenchmark {
    private static final int BATCH = Integer.getInteger("bench.batch", 500);
    private final StringBuilder out = new StringBuilder();

    private void row(String name, double v) {
        out.append(String.format(Locale.ROOT, "| %s | %.1f |\n", name, v));
    }

    private static double timeMs(Callable<?> c) throws Exception {
        long t = System.nanoTime();
        c.call();
        return (System.nanoTime() - t) / 1e6;
    }

    /** Median of {@code runs} timings after one warm-up. */
    private static double medianMs(int runs, Callable<?> c) throws Exception {
        c.call();
        double[] d = new double[runs];
        for (int i = 0; i < runs; i++) d[i] = timeMs(c);
        Arrays.sort(d);
        return d[runs / 2];
    }

    /** Median of {@code runs} timings where each run starts after a write, so cached results are invalid. */
    private static double coldMs(Database db, int runs, Callable<?> c) throws Exception {
        double[] d = new double[runs];
        for (int i = 0; i < runs; i++) {
            long v = i;
            db.tx(cn -> Sql.update(cn,
                    "INSERT OR REPLACE INTO settings(key, value, updated_utc) VALUES('bench.bump', ?, 'x')", v));
            d[i] = timeMs(c);
        }
        Arrays.sort(d);
        return d[runs / 2];
    }

    private static List<ScannedEntry> entries(Path root, int from, int to) {
        List<ScannedEntry> l = new ArrayList<>(to - from);
        long old = System.currentTimeMillis() - 400L * 86_400_000L;
        String[] exts = {"txt", "log", "csv", "pdf", "docx", "png", "zip", "md", "json", "xml"};
        for (int i = from; i < to; i++) {
            int d = i / 50;
            Path dir = root.resolve("d" + (d % 20)).resolve("e" + (d / 20 % 50)).resolve("f" + d);
            String ext = exts[i % exts.length];
            String name = "file-" + i + "." + ext;
            Path p = dir.resolve(name);
            l.add(new ScannedEntry(p, root.relativize(p).toString(), dir.toString().toLowerCase(Locale.ROOT), name, ext,
                    i % 7 == 0 ? "Archives" : "Documents", false, false, "FAKEVOL1:" + i, "FAKEVOL1",
                    1000 + (i * 37L) % 60000, 4096L * (1 + (i % 16)), true, old, old, old, 1, 0, Set.of(), false));
        }
        return l;
    }

    @Test
    void run() throws Exception {
        int n = Integer.parseInt(System.getProperty("bench.n", "100000"));
        Path base = Path.of("target", "bench-db");
        Files.createDirectories(base);
        Path dbFile = base.resolve("idlestow-" + System.nanoTime() + ".db");
        Path rootDir = Files.createDirectories(base.resolve("root")).toAbsolutePath();
        try (Database db = Database.open(dbFile)) {
            Clock clock = Clock.systemUTC();
            ItemRepository items = new ItemRepository(db, clock);
            RootRepository roots = new RootRepository(db, clock);
            CandidateRepository cands = new CandidateRepository(db, clock);
            HistoryRepository hist = new HistoryRepository(db, clock);
            Queries q = new Queries(db);
            FakePlatform platform = new FakePlatform();
            long rootId = roots.insert(rootDir, "root", "FAKEVOL1");
            roots.upsertVolume(platform.fileInspector().volumeOf(rootDir));
            long gen = roots.nextGeneration(rootId);
            List<ScannedEntry> all = entries(rootDir, 0, n);

            out.append("| Step | ms |\n|---|---:|\n");
            row("initial upsert of " + n + " rows (batches of " + BATCH + ")", timeMs(() -> {
                for (int i = 0; i < n; i += BATCH) items.upsertBatch(rootId, gen, all.subList(i, Math.min(n, i + BATCH)));
                return null;
            }));
            long gen2 = roots.nextGeneration(rootId);
            row("unchanged re-upsert of " + n + " rows", timeMs(() -> {
                for (int i = 0; i < n; i += BATCH) items.upsertBatch(rootId, gen2, all.subList(i, Math.min(n, i + BATCH)));
                return null;
            }));
            row("finalizeScan (nothing to remove)", timeMs(() -> items.finalizeScan(rootId, gen2, null)));

            MonitoredRoot root = roots.byId(rootId).orElseThrow();
            PolicyEngine policy = new PolicyEngine(new RulesRepository(db, clock),
                    new ProtectedRules(platform.protectedLocations(), null));
            AnalysisService analysis = new AnalysisService(db, items, roots, cands, policy, platform.nativeCompression(),
                    new UnavailableWorker("benchmark"), clock);
            Settings s = Settings.defaults().with("policy.observationDays", "0");
            row("AnalysisService.analyze (classify + aggregates)", timeMs(() ->
                    analysis.analyze(root, s, 0, new CancellationToken(), m -> { })));

            // synthetic recommendations for 10% of the files
            List<NewCandidate> fresh = new ArrayList<>();
            List<IndexedItem> page = items.filesPage(rootId, 0, n);
            for (int i = 0; i < page.size(); i += 10) {
                IndexedItem it = page.get(i);
                fresh.add(new NewCandidate(it.id(), Method.NATIVE, 500 + i % 9000, "sampled", "reason text", "Default",
                        "INACTIVE", "HIGH", "", false, it.logicalSize(), it.modifiedMs(), it.fileKey()));
            }
            row("candidates.replaceForRoot (" + fresh.size() + " new)", timeMs(() -> {
                cands.replaceForRoot(rootId, fresh);
                return null;
            }));
            row("candidates.replaceForRoot again (" + fresh.size() + " existing)", timeMs(() -> {
                cands.replaceForRoot(rootId, fresh);
                return null;
            }));
            for (int i = 0; i < 2000; i++) {
                hist.add(new HistoryRepository.Entry(0, null, null, "p" + i, null, "OPTIMIZE", "NATIVE", "MANUAL", "x",
                        "p", 1000L, 8192L, 4096L, 4096L, 4096L, "OK", "r", "SUCCEEDED", null));
            }

            out.append("\n| Query (median of 7; cold = first call after a write, warm = repeat call) | ms |\n|---|---:|\n");
            row("totals() cold", coldMs(db, 7, q::totals));
            row("totals() warm", medianMs(7, q::totals));
            row("currentReclaimed() cold", coldMs(db, 7, q::currentReclaimed));
            row("pendingAfterRetention() cold", coldMs(db, 7, q::pendingAfterRetention));
            row("byCategory() cold", coldMs(db, 7, q::byCategory));
            row("byCategory() warm", medianMs(7, q::byCategory));
            row("byRoot() cold", coldMs(db, 7, q::byRoot));
            row("byActivity() cold", coldMs(db, 7, q::byActivity));
            row("largestCandidates(10) cold", coldMs(db, 7, () -> q.largestCandidates(10)));
            row("effectiveness() cold", coldMs(db, 7, q::effectiveness));
            row("candidates.pendingCount()", medianMs(7, cands::pendingCount));
            row("candidates.pendingSavings()", medianMs(7, cands::pendingSavings));
            row("candidates.list(PENDING, SAVINGS, 200)", medianMs(7, () ->
                    cands.list(List.of(CandidateStatus.PENDING), null, null, CandidateRepository.Sort.SAVINGS, 200)));
            row("candidates.list(PENDING, IDLE, 200)", medianMs(7, () ->
                    cands.list(List.of(CandidateStatus.PENDING), null, null, CandidateRepository.Sort.IDLE, 200)));
            row("history.savingsPerDay(30)", medianMs(7, () -> hist.savingsPerDay(30)));
            row("explorer: folder children, sort name", medianMs(7, () ->
                    q.list(rootDir.resolve("d0").resolve("e0"), null, Queries.Filter.ALL,
                            Queries.SortField.NAME, true, 0, 500)));
            row("explorer: whole-root, filter INACTIVE_CANDIDATES", medianMs(7, () ->
                    q.list(rootDir, null, Queries.Filter.INACTIVE_CANDIDATES, Queries.SortField.SIZE, false, 0, 500)));
            row("explorer: search 'file-12' in root", medianMs(7, () ->
                    q.list(rootDir, "file-12", Queries.Filter.ALL, Queries.SortField.NAME, true, 0, 500)));
            row("items.filesPage(1000 rows from the middle)", medianMs(7, () -> items.filesPage(rootId, n / 2, 1000)));
            row("items.countFiles()", medianMs(7, items::countFiles));
            // a full paged walk like analyze() does
            row("items.filesPage full walk (pages of 1000)", timeMs(() -> {
                long after = 0;
                while (true) {
                    List<IndexedItem> p = items.filesPage(rootId, after, 1000);
                    if (p.isEmpty()) break;
                    after = p.get(p.size() - 1).id();
                }
                return null;
            }));

            out.append("\n```\n");
            String[] plans = {
                    "SELECT * FROM indexed_items WHERE root_id=1 AND is_dir=0 AND present=1 AND id>500 ORDER BY id LIMIT 1000",
                    "SELECT COUNT(*) FROM indexed_items WHERE is_dir=0 AND present=1",
                    "SELECT category, COUNT(*), SUM(logical_size) FROM indexed_items WHERE is_dir=0 AND present=1 GROUP BY category",
                    "SELECT COALESCE(SUM(s),0) FROM (SELECT MAX(logical_size) s FROM indexed_items WHERE is_dir=0 AND present=1 GROUP BY COALESCE(file_key, 'p'||id))",
                    "SELECT ac.classification, COUNT(*), SUM(i.logical_size) FROM activity_classifications ac JOIN indexed_items i ON i.id=ac.item_id WHERE i.present=1 GROUP BY ac.classification",
                    "SELECT i.path, oc.est_savings, oc.method FROM optimization_candidates oc JOIN indexed_items i ON i.id=oc.item_id WHERE oc.status='PENDING' ORDER BY oc.est_savings DESC LIMIT 10",
                    "SELECT COALESCE(SUM(est_savings),0) FROM optimization_candidates WHERE status IN ('PENDING','APPROVED')",
                    "SELECT * FROM optimization_history WHERE at_utc >= date('now','-30 days')",
                    "SELECT i.*, oc.est_savings FROM indexed_items i LEFT JOIN optimization_candidates oc ON oc.item_id=i.id"
                            + " AND oc.status IN ('PENDING','APPROVED','POSTPONED') WHERE 1=1 AND i.path_key LIKE 'x%'"
                            + " ESCAPE '\\' AND i.is_dir=0 AND oc.id IS NOT NULL AND (i.present=1 OR i.opt_status='ARCHIVED')"
                            + " ORDER BY i.is_dir DESC, i.logical_size DESC LIMIT 500",
                    "SELECT oc.*, i.path FROM optimization_candidates oc JOIN indexed_items i ON i.id=oc.item_id"
                            + " LEFT JOIN activity_classifications ac ON ac.item_id=oc.item_id WHERE oc.status IN ('PENDING')"
                            + " ORDER BY COALESCE(ac.idle_days,-1) DESC LIMIT 200",
            };
            db.read(c -> {
                for (String p : plans) {
                    out.append(p).append('\n');
                    try (var st = c.createStatement(); var rs = st.executeQuery("EXPLAIN QUERY PLAN " + p)) {
                        while (rs.next()) out.append("    ").append(rs.getString("detail")).append('\n');
                    }
                }
                return null;
            });
            out.append("```\n");
            db.checkpoint();
            out.append(String.format(Locale.ROOT, "%nDB size: %.1f MiB%n", Files.size(dbFile) / 1048576.0));
        }
        String env = "Hardware: " + System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown") + ", "
                + Runtime.getRuntime().availableProcessors() + " logical CPUs, max heap "
                + Runtime.getRuntime().maxMemory() / 1048576 + " MiB; OS: " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + "; Java " + System.getProperty("java.version") + "; rows: " + n + "\n\n";
        String report = "# DB benchmark\n\n" + env + out;
        Files.writeString(Path.of("target", "benchmark-db.md"), report, StandardCharsets.UTF_8);
        System.out.println(report);
    }
}
