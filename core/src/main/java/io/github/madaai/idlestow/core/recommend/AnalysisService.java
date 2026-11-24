package io.github.madaai.idlestow.core.recommend;

import io.github.madaai.idlestow.core.activity.ActivityClassifier;
import io.github.madaai.idlestow.core.db.CandidateRepository;
import io.github.madaai.idlestow.core.db.CandidateRepository.NewCandidate;
import io.github.madaai.idlestow.core.db.Database;
import io.github.madaai.idlestow.core.db.ItemRepository;
import io.github.madaai.idlestow.core.db.RootRepository;
import io.github.madaai.idlestow.core.db.Sql;
import io.github.madaai.idlestow.core.ipc.OptimizationWorker;
import io.github.madaai.idlestow.core.ipc.WorkerException;
import io.github.madaai.idlestow.core.model.ActivityAssessment;
import io.github.madaai.idlestow.core.model.Enums.ActivityClass;
import io.github.madaai.idlestow.core.model.Enums.Confidence;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.OptStatus;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.platform.NativeCompression;
import io.github.madaai.idlestow.core.platform.VolumeInfo;
import io.github.madaai.idlestow.core.policy.PolicyEngine;
import io.github.madaai.idlestow.core.policy.PolicyEngine.EffectivePolicy;
import io.github.madaai.idlestow.core.policy.PolicyEngine.Protection;
import io.github.madaai.idlestow.core.policy.ProtectedRules;
import io.github.madaai.idlestow.core.settings.Settings;
import io.github.madaai.idlestow.core.util.CancellationToken;
import io.github.madaai.idlestow.core.util.PathUtil;
import io.github.madaai.idlestow.core.util.Sizes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns the raw index into activity classifications, compressibility estimates, recommendations
 * and per-directory aggregates. Works page by page so memory stays bounded for large roots.
 *
 * <p>Mixed-activity folders are handled naturally: every file is evaluated on its own evidence and
 * policy; directory aggregates only summarize, they never make a decision for their descendants.
 */
public final class AnalysisService {
    private static final Logger LOG = LoggerFactory.getLogger(AnalysisService.class);
    private static final int PAGE = 1000;

    private final Database db;
    private final ItemRepository items;
    private final RootRepository roots;
    private final CandidateRepository candidates;
    private final PolicyEngine policy;
    private final NativeCompression nativeCompression;
    private final OptimizationWorker worker;
    private final ActivityClassifier classifier = new ActivityClassifier();
    private final Clock clock;

    public AnalysisService(Database db, ItemRepository items, RootRepository roots, CandidateRepository candidates,
                           PolicyEngine policy, NativeCompression nativeCompression, OptimizationWorker worker,
                           Clock clock) {
        this.db = db;
        this.items = items;
        this.roots = roots;
        this.candidates = candidates;
        this.policy = policy;
        this.nativeCompression = nativeCompression;
        this.worker = worker;
        this.clock = clock;
    }

    public record Summary(long files, long classified, long candidates, long estimated, long estimateBudgetLeft,
                          String note) { }

    /** Full analysis of one root. {@code estimateBudget} bounds how many files are content-sampled this pass. */
    public Summary analyze(MonitoredRoot root, Settings s, int estimateBudget, CancellationToken token,
                           Consumer<String> status) throws SQLException {
        VolumeInfo vol = root.volumeId() == null ? null : roots.volume(root.volumeId()).orElse(null);
        if (vol == null) {
            vol = new VolumeInfo("unknown", "", "unknown", false, "Volume not scanned yet", -1, false, false,
                    VolumeInfo.AccessTracking.UNKNOWN, "Unknown");
        }
        Map<Long, long[]> evidence = loadEvidence(root.id());
        List<NewCandidate> fresh = new ArrayList<>();
        Map<String, Agg> aggs = new HashMap<>();
        long now = clock.millis();
        long files = 0, classified = 0, estimated = 0;
        int budget = estimateBudget;
        boolean workerOk = true;
        String note = "";
        long after = 0;
        Path rootPath = PathUtil.normalize(root.path());
        while (true) {
            token.checkpoint();
            List<IndexedItem> page = items.filesPage(root.id(), after, PAGE);
            if (page.isEmpty()) break;
            List<Object[]> cls = new ArrayList<>(page.size());
            for (IndexedItem it : page) {
                after = it.id();
                files++;
                Protection prot = policy.protection(it.path(), false, it.logicalSize(), it.category(), it.volumeId(), root);
                EffectivePolicy eff = policy.effective(it.path(), it.extension(), root, s);
                long[] ev = evidence.getOrDefault(it.id(), new long[3]);
                ActivityAssessment a = classifier.classify(new ActivityClassifier.Input(it, prot,
                        vol.accessTracking(), eff.inactivityDays(), eff.archival(), s.observationDays(), ev[0], ev[1],
                        ev[2], now));
                cls.add(new Object[]{it.id(), a});
                classified++;

                long cluster = vol.clusterSize() > 0 ? vol.clusterSize() : 4096;
                long actual = 0;
                if (it.optStatus() == OptStatus.NATIVE_COMPRESSED && it.physicalExact() && it.physicalSize() >= 0) {
                    actual = Math.max(0, Sizes.roundUp(it.logicalSize(), cluster) - it.physicalSize());
                }

                // Recommendation decision for this single file.
                Decision d = decide(it, a, eff, prot, root, vol, s);
                long est = 0;
                if (d.method != null) {
                    IndexedItem.Estimate e = it.estimate();
                    if ((e == null || !e.validFor(it.logicalSize(), it.modifiedMs())) && budget > 0 && workerOk) {
                        long t0 = clock.millis();
                        try {
                            OptimizationWorker.Estimate w = worker.estimate(it.path(), s.maxSampleBytes(), s.profile());
                            e = new IndexedItem.Estimate(w.zipRatio(), w.ntfsSavings(), w.zipSavings(), w.zstdSavings(),
                                    w.detectedFormat(), w.basis(), it.logicalSize(), it.modifiedMs());
                            items.saveEstimate(it.id(), e);
                            estimated++;
                            budget--;
                        } catch (WorkerException ex) {
                            if (WorkerException.WORKER_UNAVAILABLE.equals(ex.code())) {
                                workerOk = false;
                                note = "The compression worker is unavailable, so savings could not be estimated: "
                                        + ex.getMessage();
                            }
                            LOG.debug("Estimate failed for {}: {}", it.path(), ex.getMessage());
                        } finally {
                            items.recordSelfRead(it.id(), t0, clock.millis());
                        }
                    }
                    if (e != null && e.validFor(it.logicalSize(), it.modifiedMs())) {
                        Long savings = switch (d.method) {
                            case NATIVE -> e.ntfsSavings();
                            case ZIP_COPY, ZIP_REPLACE -> e.zipSavings();
                            case TZST_COPY, TZST_REPLACE -> e.zstdSavings();
                        };
                        est = savings == null ? 0 : savings;
                        boolean enough = est >= s.minSavingsBytes()
                                && est * 100 >= (long) s.minSavingsPercent() * Math.max(1, it.logicalSize());
                        if (enough) {
                            boolean auto = d.autoEligible;
                            String reason = a.explanation() + " " + d.methodNote + " Estimated savings: "
                                    + Sizes.format(est) + " (" + e.basis() + " estimate"
                                    + (e.detectedFormat() != null && !"unknown".equals(e.detectedFormat())
                                    ? ", detected " + e.detectedFormat() : "") + ").";
                            fresh.add(new NewCandidate(it.id(), d.method, est, e.basis(), reason, eff.source(),
                                    a.classification().name(), a.confidence().name(), String.join(" ", d.warnings),
                                    auto, it.logicalSize(), it.modifiedMs(), it.fileKey()));
                        } else {
                            est = 0;
                        }
                    }
                }
                addToAggregates(aggs, rootPath, it, a, prot, est, actual);
            }
            items.saveClassifications(cls);
        }
        if (fresh.size() > 0 || files > 0) candidates.replaceForRoot(root.id(), fresh);
        saveAggregates(root.id(), aggs);
        status.accept("Analysis complete: " + fresh.size() + " recommendations");
        return new Summary(files, classified, fresh.size(), estimated, budget, note);
    }

    record Decision(Method method, boolean autoEligible, String methodNote, List<String> warnings) { }

    /** Decides whether (and how) a single file should be recommended. */
    Decision decide(IndexedItem it, ActivityAssessment a, EffectivePolicy eff, Protection prot, MonitoredRoot root,
                    VolumeInfo vol, Settings s) {
        List<String> warnings = new ArrayList<>();
        if (prot.blocked() || eff.neverOptimize()) return new Decision(null, false, "", warnings);
        if (it.special() || !it.present() || it.optStatus() != OptStatus.NONE) return new Decision(null, false, "", warnings);
        if (ProtectedRules.isIdleStowArchive(it.name())) return new Decision(null, false, "", warnings);
        if (it.logicalSize() < s.minFileSize() || (s.maxFileSize() > 0 && it.logicalSize() > s.maxFileSize())) {
            return new Decision(null, false, "", warnings);
        }
        if (it.has(IndexedItem.ATTR_CLOUD) || it.has(IndexedItem.ATTR_OFFLINE) || it.has(IndexedItem.ATTR_REPARSE)) {
            return new Decision(null, false, "", warnings);
        }
        boolean inactiveish = a.classification() == ActivityClass.INACTIVE
                || a.classification() == ActivityClass.POTENTIALLY_INACTIVE;
        if (!inactiveish || !a.confidence().atLeast(s.minConfidenceRecommend())) {
            return new Decision(null, false, "", warnings);
        }
        boolean cloud = policy.protectedRules().inCloudSyncFolder(it.path());
        boolean nativeOk = nativeCompression.implemented() && nativeCompression.supportedOn(vol)
                && !it.has(IndexedItem.ATTR_ENCRYPTED) && !it.has(IndexedItem.ATTR_SPARSE)
                && !it.nativeCompressed() && eff.allowedMethods().contains(Method.NATIVE);
        if (cloud) warnings.add("This file is in a cloud-synchronized folder; only manual native compression is offered.");
        if (it.has(IndexedItem.ATTR_READONLY)) warnings.add("Read-only file.");
        boolean autoBase = a.classification() == ActivityClass.INACTIVE
                && a.confidence().atLeast(eff.minConfidenceAuto()) && root.autonomyEnabled() && !cloud;
        if (nativeOk) {
            return new Decision(Method.NATIVE, autoBase,
                    "Native transparent compression is supported (the file keeps its path and opens normally).",
                    warnings);
        }
        if (cloud) return new Decision(null, false, "", warnings);
        Method replace = s.fallbackMethod() == Method.TZST_COPY || s.fallbackMethod() == Method.TZST_REPLACE
                ? Method.TZST_REPLACE : Method.ZIP_REPLACE;
        if (s.archiveReplaceEnabled() && eff.allowedMethods().contains(replace) && it.linkCount() == 1
                && it.altStreams() == 0 && !it.has(IndexedItem.ATTR_SYSTEM) && !it.has(IndexedItem.ATTR_ENCRYPTED)) {
            warnings.add("The file will be replaced by a verified archive: other programs cannot open it until it is"
                    + " restored. The original is retained for " + s.retentionDays() + " days.");
            // Archive replacement is never autonomous unless a policy explicitly allows the method.
            boolean auto = autoBase && eff.allowedMethods().contains(replace) && explicitlyAllowed(eff, replace);
            return new Decision(replace, auto, "Native compression is not available here ("
                    + vol.nativeCompressionNote() + "); archive replacement is enabled in your settings.", warnings);
        }
        return new Decision(null, false, "", warnings);
    }

    private static boolean explicitlyAllowed(EffectivePolicy eff, Method m) {
        // EffectivePolicy.allowedMethods is "all" when no policy restricts methods; that is not an explicit opt-in.
        return eff.allowedMethods().size() < Method.values().length && eff.allowedMethods().contains(m);
    }

    // ---------------------------------------------------------------- evidence preload

    /** item → [openedViaIdleStow, userDeclaredActive, userConfirmedInactive] latest timestamps. */
    private Map<Long, long[]> loadEvidence(long rootId) throws SQLException {
        Map<Long, long[]> m = new HashMap<>();
        db.read(c -> {
            for (Object[] r : Sql.list(c, "SELECT e.item_id, e.kind, MAX(e.event_ms) FROM activity_evidence e"
                            + " JOIN indexed_items i ON i.id=e.item_id WHERE i.root_id=? AND e.kind IN"
                            + " ('OPENED_VIA_IDLESTOW','USER_DECLARED_ACTIVE','USER_CONFIRMED_INACTIVE')"
                            + " GROUP BY e.item_id, e.kind",
                    rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getLong(3)}, rootId)) {
                long[] v = m.computeIfAbsent((Long) r[0], k -> new long[3]);
                switch ((String) r[1]) {
                    case "OPENED_VIA_IDLESTOW" -> v[0] = (Long) r[2];
                    case "USER_DECLARED_ACTIVE" -> v[1] = (Long) r[2];
                    default -> v[2] = (Long) r[2];
                }
            }
            return null;
        });
        return m;
    }

    // ---------------------------------------------------------------- aggregates

    static final class Agg {
        long files, dirs, active, inactive, unknown, excluded, optimized, logical, physical, est, actual;
    }

    private void addToAggregates(Map<String, Agg> aggs, Path rootPath, IndexedItem it, ActivityAssessment a,
                                 Protection prot, long est, long actual) {
        for (Path dir = it.path().getParent(); dir != null && PathUtil.isWithin(dir, rootPath); dir = dir.getParent()) {
            Agg g = aggs.computeIfAbsent(PathUtil.key(dir), k -> new Agg());
            g.files++;
            g.logical += it.logicalSize();
            g.physical += it.physicalSize() >= 0 ? it.physicalSize() : it.logicalSize();
            switch (a.classification()) {
                case ACTIVE, PROBABLY_ACTIVE -> g.active++;
                case INACTIVE, POTENTIALLY_INACTIVE -> g.inactive++;
                case UNKNOWN -> g.unknown++;
                default -> g.excluded++;
            }
            if (it.optStatus() == OptStatus.NATIVE_COMPRESSED || it.optStatus() == OptStatus.ARCHIVE_COPY) g.optimized++;
            g.est += est;
            g.actual += actual;
            if (dir.equals(rootPath)) break;
        }
    }

    private void saveAggregates(long rootId, Map<String, Agg> aggs) throws SQLException {
        String now = clock.instant().toString();
        db.tx(c -> {
            Sql.update(c, "DELETE FROM directory_aggregates WHERE root_id=?", rootId);
            try (var ps = c.prepareStatement("INSERT OR REPLACE INTO directory_aggregates(path_key, root_id, total_files,"
                    + " total_dirs, active_files, inactive_files, unknown_files, excluded_files, optimized_files,"
                    + " logical_bytes, physical_bytes, est_reclaimable, actual_reclaimed, computed_utc)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (Map.Entry<String, Agg> e : aggs.entrySet()) {
                    Agg g = e.getValue();
                    Sql.bind(ps, e.getKey(), rootId, g.files, g.dirs, g.active, g.inactive, g.unknown, g.excluded,
                            g.optimized, g.logical, g.physical, g.est, g.actual, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    /** Explicitly mark confidence (exposed for "I confirm this is inactive" / "this is active" actions). */
    public static Confidence minAuto(Settings s) {
        return s.minConfidenceAuto();
    }
}
