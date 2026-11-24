package io.github.madaai.idlestow.core.engine;

import io.github.madaai.idlestow.core.activity.ActivityClassifier;
import io.github.madaai.idlestow.core.db.CandidateRepository;
import io.github.madaai.idlestow.core.db.CandidateRepository.Candidate;
import io.github.madaai.idlestow.core.db.CandidateRepository.PlanItem;
import io.github.madaai.idlestow.core.db.Database;
import io.github.madaai.idlestow.core.db.HistoryRepository;
import io.github.madaai.idlestow.core.db.ItemRepository;
import io.github.madaai.idlestow.core.db.JobRepository;
import io.github.madaai.idlestow.core.db.Queries;
import io.github.madaai.idlestow.core.db.RecoveryRepository;
import io.github.madaai.idlestow.core.db.RootRepository;
import io.github.madaai.idlestow.core.db.RulesRepository;
import io.github.madaai.idlestow.core.index.FileWatcher;
import io.github.madaai.idlestow.core.index.Scanner;
import io.github.madaai.idlestow.core.ipc.OptimizationWorker;
import io.github.madaai.idlestow.core.jobs.Authorizer;
import io.github.madaai.idlestow.core.jobs.CrashRecovery;
import io.github.madaai.idlestow.core.jobs.JobContext;
import io.github.madaai.idlestow.core.jobs.JobManager;
import io.github.madaai.idlestow.core.model.ActivityAssessment;
import io.github.madaai.idlestow.core.model.Enums.ActivityClass;
import io.github.madaai.idlestow.core.model.Enums.AuthorizationMode;
import io.github.madaai.idlestow.core.model.Enums.CandidateStatus;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.OptStatus;
import io.github.madaai.idlestow.core.model.Enums.Origin;
import io.github.madaai.idlestow.core.model.Enums.RecoveryKind;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.platform.PlatformServices;
import io.github.madaai.idlestow.core.platform.VolumeInfo;
import io.github.madaai.idlestow.core.policy.PolicyEngine;
import io.github.madaai.idlestow.core.policy.PolicyEngine.EffectivePolicy;
import io.github.madaai.idlestow.core.policy.PolicyEngine.Protection;
import io.github.madaai.idlestow.core.policy.ProtectedRules;
import io.github.madaai.idlestow.core.recommend.AnalysisService;
import io.github.madaai.idlestow.core.settings.Settings;
import io.github.madaai.idlestow.core.settings.SettingsStore;
import io.github.madaai.idlestow.core.util.CancellationToken;
import io.github.madaai.idlestow.core.util.Json;
import io.github.madaai.idlestow.core.util.PathUtil;
import io.github.madaai.idlestow.core.util.Sizes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The single entry point used by the desktop UI and the background process.
 *
 * <p>The engine owns persistence, indexing, analysis, the job manager and the scheduler. It never
 * touches JavaFX; listeners receive coarse change notifications on engine threads.
 */
public final class IdleStowEngine implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(IdleStowEngine.class);

    public enum Event { ROOTS, SCAN, ANALYSIS, JOBS, SETTINGS, RECOVERY, STATUS }

    private final EngineConfig config;
    private final Database db;
    private final RootRepository roots;
    private final ItemRepository items;
    private final RulesRepository rules;
    private final CandidateRepository candidates;
    private final JobRepository jobs;
    private final HistoryRepository history;
    private final RecoveryRepository recovery;
    private final Queries queries;
    private final SettingsStore settings;
    private final PolicyEngine policy;
    private final Scanner scanner;
    private final AnalysisService analysis;
    private final JobManager jobManager;
    private final JobContext jobContext;
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService scanExecutor;
    private final ScheduledExecutorService scheduler;
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();
    private volatile FileWatcher watcher;
    private volatile CancellationToken scanToken = new CancellationToken();
    private volatile ScanStatus scanStatus = ScanStatus.IDLE;
    private volatile CrashRecovery.Report startupRecovery;
    private volatile String lastAutonomyMessage = "Autonomous optimization is off";
    private volatile long nextScanAtMs;
    private volatile long pausedUntilMs;

    /** Current indexing status for the status bar. */
    public record ScanStatus(boolean running, String rootName, long files, long bytes, String message,
                             boolean paused) {
        static final ScanStatus IDLE = new ScanStatus(false, "", 0, 0, "Idle", false);
    }

    public IdleStowEngine(EngineConfig config) throws Database.DatabaseUnavailableException, SQLException {
        this.config = config;
        this.db = Database.open(AppDirectories.databaseFile(config.dataDir()));
        var clock = config.clock();
        this.roots = new RootRepository(db, clock);
        this.items = new ItemRepository(db, clock);
        this.rules = new RulesRepository(db, clock);
        this.candidates = new CandidateRepository(db, clock);
        this.jobs = new JobRepository(db, clock);
        this.history = new HistoryRepository(db, clock);
        this.recovery = new RecoveryRepository(db, clock);
        this.queries = new Queries(db);
        this.settings = new SettingsStore(db, clock);
        this.policy = new PolicyEngine(rules, new ProtectedRules(config.platform().protectedLocations(), config.dataDir()));
        this.scanner = new Scanner(config.platform().fileInspector(), items, roots);
        this.analysis = new AnalysisService(db, items, roots, candidates, policy, config.platform().nativeCompression(),
                config.worker(), clock);
        Authorizer authorizer = new Authorizer(roots, policy);
        this.jobContext = new JobContext(config.platform(), config.worker(), items, roots, policy, authorizer, jobs,
                history, recovery, candidates, settings, config.dataDir(), clock, config.faults());
        this.jobManager = new JobManager(jobContext, settings.get().jobConcurrency());
        this.jobManager.addListener(id -> fire(Event.JOBS));
        this.scanExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "idlestow-indexer");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idlestow-scheduler");
            t.setDaemon(true);
            return t;
        });
        settings.addListener(s -> {
            fire(Event.SETTINGS);
            reschedule();
        });
    }

    /** Runs crash recovery and starts background services according to settings. */
    public void start() {
        try {
            startupRecovery = new CrashRecovery(jobContext).run();
            if (startupRecovery.inspected() > 0) {
                LOG.info("Startup recovery inspected {} interrupted operations", startupRecovery.inspected());
            }
        } catch (SQLException e) {
            LOG.error("Startup recovery failed", e);
        }
        reschedule();
    }

    // =================================================================================== accessors

    public Settings settings() { return settings.get(); }
    public SettingsStore settingsStore() { return settings; }
    public Queries queries() { return queries; }
    public HistoryRepository history() { return history; }
    public RecoveryRepository recoveryRecords() { return recovery; }
    public JobRepository jobs() { return jobs; }
    public JobManager jobManager() { return jobManager; }
    public RulesRepository rules() { return rules; }
    public PlatformServices platform() { return config.platform(); }
    public OptimizationWorker worker() { return config.worker(); }
    public Database database() { return db; }
    public Path dataDir() { return config.dataDir(); }
    public boolean readOnly() { return config.readOnly(); }
    public ScanStatus scanStatus() { return scanStatus; }
    public Optional<CrashRecovery.Report> startupRecovery() { return Optional.ofNullable(startupRecovery); }
    public String autonomyMessage() { return lastAutonomyMessage; }
    public long nextScanAtMs() { return nextScanAtMs; }

    public void addListener(Consumer<Event> l) {
        listeners.add(l);
    }

    private void fire(Event e) {
        for (Consumer<Event> l : listeners) {
            try {
                l.accept(e);
            } catch (RuntimeException ex) {
                LOG.debug("Listener failed", ex);
            }
        }
    }

    // =================================================================================== roots

    public List<MonitoredRoot> roots() throws SQLException {
        return roots.all();
    }

    /**
     * Authorizes a new monitored folder. Refuses protected locations and overlapping roots (overlaps would
     * double count storage and blur authorization boundaries).
     */
    public MonitoredRoot addRoot(Path folder) throws SQLException, IOException {
        Path lexical = PathUtil.normalize(folder);
        if (!Files.isDirectory(lexical)) throw new IOException("Not a folder: " + lexical);
        // Canonical long-name path (resolves 8.3 short names, letter case and links) so protections cannot be
        // bypassed by spelling a protected location differently.
        Path p = lexical.toRealPath();
        for (Path candidate : List.of(lexical, p)) {
            Optional<String> prot = policy.protectedRules().protectionReason(candidate, true);
            if (prot.isPresent()) throw new IOException("This folder cannot be monitored: " + prot.get());
        }
        if (PathUtil.isWithin(config.dataDir(), p)) {
            throw new IOException("This folder contains IdleStow's own data directory and cannot be monitored");
        }
        for (MonitoredRoot r : roots.all()) {
            if (PathUtil.isWithin(p, r.path()) || PathUtil.isWithin(r.path(), p)) {
                throw new IOException("This folder overlaps the monitored folder " + r.path()
                        + ". Overlapping folders are not allowed.");
            }
        }
        VolumeInfo vol = config.platform().fileInspector().volumeOf(p);
        roots.upsertVolume(vol);
        String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
        long id = roots.insert(p, name, vol.volumeId());
        fire(Event.ROOTS);
        reschedule();
        return roots.byId(id).orElseThrow();
    }

    /** Revokes authorization immediately; running autonomous work stops at the next safe point. */
    public void revokeRoot(long rootId) throws SQLException {
        roots.revoke(rootId);
        if (watcher != null) watcher.unwatch(rootId);
        fire(Event.ROOTS);
    }

    public void reauthorizeRoot(long rootId) throws SQLException {
        roots.reauthorize(rootId);
        fire(Event.ROOTS);
        reschedule();
    }

    /** Removes a root and its index. Recovery records (and archives/retained originals on disk) are kept. */
    public void removeRoot(long rootId) throws SQLException {
        if (watcher != null) watcher.unwatch(rootId);
        roots.delete(rootId);
        fire(Event.ROOTS);
    }

    public void setRootAutonomy(long rootId, boolean enabled) throws SQLException {
        roots.setAutonomy(rootId, enabled);
        fire(Event.ROOTS);
    }

    public void setRootArchival(long rootId, boolean archival) throws SQLException {
        roots.setArchival(rootId, archival);
        fire(Event.ROOTS);
    }

    public Optional<VolumeInfo> volume(String volumeId) throws SQLException {
        return roots.volume(volumeId);
    }

    public VolumeInfo inspectVolume(Path p) throws IOException {
        return config.platform().fileInspector().volumeOf(p);
    }

    // =================================================================================== scanning

    /** Queues a read-only scan + analysis of one root (or all active roots when {@code rootId} is null). */
    public java.util.concurrent.Future<?> scan(Long rootId) {
        return scanExecutor.submit(() -> {
            try {
                List<MonitoredRoot> list = rootId == null ? roots.active()
                        : roots.byId(rootId).filter(MonitoredRoot::active).map(List::of).orElse(List.of());
                for (MonitoredRoot r : list) {
                    if (scanToken.isCancelled()) break;
                    scanOne(r, null);
                }
            } catch (SQLException e) {
                LOG.error("Scan failed", e);
                scanStatus = new ScanStatus(false, "", 0, 0, "Scan failed: " + e.getMessage(), false);
                fire(Event.SCAN);
            } finally {
                if (scanToken.isCancelled()) scanToken = new CancellationToken();
            }
        });
    }

    private void scanOne(MonitoredRoot r, Path subtree) throws SQLException {
        CancellationToken token = scanToken;
        scanStatus = new ScanStatus(true, r.displayName(), 0, 0, "Scanning " + r.displayName(), token.isPaused());
        fire(Event.SCAN);
        try {
            Scanner.Result res = scanner.scan(r, subtree, token, p -> {
                scanStatus = new ScanStatus(!p.finished(), r.displayName(), p.files(), p.bytes(),
                        p.finished() ? p.status() : "Indexing " + r.displayName(), token.isPaused());
                fire(Event.SCAN);
            });
            if (res.completed()) {
                analyze(r, token);
            }
            scanStatus = new ScanStatus(false, r.displayName(), res.files(), res.bytes(), res.status(), false);
        } catch (IOException e) {
            scanStatus = new ScanStatus(false, r.displayName(), 0, 0, e.getMessage(), false);
            LOG.warn("Scan of {} failed: {}", r.path(), e.getMessage());
        } catch (java.util.concurrent.CancellationException e) {
            scanStatus = new ScanStatus(false, r.displayName(), 0, 0, "Cancelled", false);
        }
        fire(Event.SCAN);
        fire(Event.ANALYSIS);
    }

    private void analyze(MonitoredRoot r, CancellationToken token) throws SQLException {
        Settings s = settings.get();
        MonitoredRoot fresh = roots.byId(r.id()).orElse(r);
        scanStatus = new ScanStatus(true, r.displayName(), 0, 0, "Analyzing " + r.displayName(), false);
        fire(Event.SCAN);
        AnalysisService.Summary sum = analysis.analyze(fresh, s, estimateBudget(s), token, msg -> { });
        LOG.info("Analysis of {}: {} files, {} recommendations, {} estimated{}", r.path(), sum.files(),
                sum.candidates(), sum.estimated(), sum.note().isEmpty() ? "" : " — " + sum.note());
    }

    private static int estimateBudget(Settings s) {
        return switch (s.performanceMode()) {
            case SILENT -> 200;
            case BALANCED -> 2000;
            case PERFORMANCE -> 20000;
        };
    }

    /** Re-runs classification and recommendations without rescanning (e.g. after a policy change). */
    public java.util.concurrent.Future<?> reanalyze() {
        return scanExecutor.submit(() -> {
            try {
                for (MonitoredRoot r : roots.active()) analyze(r, scanToken);
            } catch (SQLException | RuntimeException e) {
                LOG.warn("Re-analysis failed", e);
            }
            scanStatus = new ScanStatus(false, "", 0, 0, "Analysis updated", false);
            fire(Event.SCAN);
            fire(Event.ANALYSIS);
        });
    }

    public void cancelScan() {
        scanToken.cancel();
        fire(Event.SCAN);
    }

    public void pauseScan(boolean pause) {
        if (pause) scanToken.pause();
        else scanToken.resume();
        ScanStatus st = scanStatus;
        scanStatus = new ScanStatus(st.running(), st.rootName(), st.files(), st.bytes(), st.message(), pause);
        fire(Event.SCAN);
    }

    /** Incremental update from the watcher. */
    private void onChanges(long rootId, Set<Path> paths, boolean fullRescan) {
        scanExecutor.submit(() -> {
            try {
                MonitoredRoot r = roots.byId(rootId).filter(MonitoredRoot::active).orElse(null);
                if (r == null) return;
                if (fullRescan) {
                    LOG.info("Watcher overflow for {} — reconciling with a full scan", r.path());
                    scanOne(r, null);
                    return;
                }
                for (Path p : reduceToTopmost(paths)) {
                    if (!PathUtil.isWithin(p, r.path())) continue;
                    if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                        scanner.scan(r, p, scanToken, x -> { });
                    } else {
                        items.markRemoved(p);
                    }
                }
                analyze(r, scanToken);
                fire(Event.ANALYSIS);
            } catch (SQLException | IOException | RuntimeException e) {
                LOG.warn("Incremental update failed: {}", e.toString());
            }
        });
    }

    static List<Path> reduceToTopmost(Set<Path> paths) {
        List<Path> sorted = paths.stream().map(PathUtil::normalize).sorted().toList();
        List<Path> out = new ArrayList<>();
        for (Path p : sorted) {
            boolean covered = false;
            for (Path o : out) {
                if (PathUtil.isWithin(p, o) && Files.isDirectory(o)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) out.add(p);
        }
        return out;
    }

    // =================================================================================== explorer actions

    public void pin(Path p) throws SQLException {
        rules.pin(p);
        policy.reload();
        reanalyze();
    }

    public void unpin(Path p) throws SQLException {
        rules.unpin(p);
        policy.reload();
        reanalyze();
    }

    public void exclude(Path p) throws SQLException {
        rules.addExclusion("PATH", p.toString());
        policy.reload();
        reanalyze();
    }

    public void rulesChanged() throws SQLException {
        policy.reload();
        reanalyze();
    }

    /** Records that the user opened a file through IdleStow (activity evidence). */
    public void recordOpened(long itemId) throws SQLException {
        items.addEvidence(itemId, "OPENED_VIA_IDLESTOW", config.clock().millis(), null, "Opened through IdleStow");
    }

    public void markActive(long itemId) throws SQLException {
        items.addEvidence(itemId, "USER_DECLARED_ACTIVE", config.clock().millis(), null, "Marked active by you");
        reanalyze();
    }

    public void confirmInactive(long itemId) throws SQLException {
        items.addEvidence(itemId, "USER_CONFIRMED_INACTIVE", config.clock().millis(), null, "Confirmed inactive by you");
        reanalyze();
    }

    public Optional<ActivityAssessment> classification(long itemId) throws SQLException {
        return items.classification(itemId);
    }

    public Optional<IndexedItem> item(long id) throws SQLException {
        return items.byId(id);
    }

    public PolicyEngine policy() {
        return policy;
    }

    // =================================================================================== plans

    /**
     * Computes what optimizing the selection would do. Directories are expanded to their descendants;
     * active, unknown-activity, protected, pinned, excluded or ineligible descendants are listed as untouched.
     * Individually selected files may be optimized even if active (the user chose them), with a warning.
     */
    public PlanPreview preview(List<Long> itemIds, Method method) throws SQLException {
        Settings s = settings.get();
        List<PlanPreview.Item> change = new ArrayList<>();
        List<PlanPreview.Item> untouched = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long est = 0;
        Map<Long, MonitoredRoot> rootCache = new LinkedHashMap<>();
        int cap = 20_000;
        for (Long id : itemIds) {
            IndexedItem sel = items.byId(id).orElse(null);
            if (sel == null) continue;
            List<IndexedItem> files = new ArrayList<>();
            boolean viaFolder = sel.directory();
            if (viaFolder) {
                long after = 0;
                while (files.size() < cap) {
                    List<IndexedItem> page = items.descendantsPage(sel.path(), after, 1000);
                    if (page.isEmpty()) break;
                    files.addAll(page);
                    after = page.get(page.size() - 1).id();
                }
            } else {
                files.add(sel);
            }
            for (IndexedItem it : files) {
                MonitoredRoot root = rootCache.computeIfAbsent(it.rootId(), rid -> {
                    try {
                        return roots.byId(rid).orElse(null);
                    } catch (SQLException e) {
                        return null;
                    }
                });
                ActivityAssessment a = items.classification(it.id()).orElse(new ActivityAssessment(
                        ActivityClass.UNKNOWN, io.github.madaai.idlestow.core.model.Enums.Confidence.NONE, 0, -1,
                        List.of("Not analyzed yet")));
                String reason = ineligibility(it, a, root, method, viaFolder, s);
                long e = estimate(it, method);
                PlanPreview.Item pi = new PlanPreview.Item(it.id(), it.path(), it.logicalSize(), it.modifiedMs(),
                        it.fileKey(), e,
                        a.classification().label(), a.confidence().name(), reason == null ? a.explanation() : reason);
                if (reason == null) {
                    change.add(pi);
                    est += e;
                    if (!viaFolder && (a.classification() == ActivityClass.ACTIVE
                            || a.classification() == ActivityClass.PROBABLY_ACTIVE)) {
                        warnings.add(it.name() + " was used recently.");
                    }
                } else {
                    untouched.add(pi);
                }
            }
        }
        if (method.replacesOriginal()) {
            warnings.add("Each file will be replaced by a verified archive. Other programs will not be able to open"
                    + " it until you restore it. Originals are retained for " + s.retentionDays()
                    + " days before space is reclaimed.");
        } else if (!method.transparent()) {
            warnings.add("A compressed copy is created next to each file. This uses additional space and reclaims"
                    + " nothing.");
        }
        String recoveryNote = switch (method) {
            case NATIVE -> "Fully reversible: decompress at any time from Recovery or the Explorer.";
            case ZIP_COPY, TZST_COPY -> "Originals are untouched; the copies can be deleted at any time.";
            case ZIP_REPLACE, TZST_REPLACE -> "Restorable from the verified archive; the original is retained for "
                    + s.retentionDays() + " days. After that, the archive is the only copy — keep backups.";
        };
        return new PlanPreview(method, change, untouched, est, warnings, method.replacesOriginal(), recoveryNote);
    }

    private long estimate(IndexedItem it, Method m) {
        IndexedItem.Estimate e = it.estimate();
        if (e == null || !e.validFor(it.logicalSize(), it.modifiedMs())) return 0;
        Long v = switch (m) {
            case NATIVE -> e.ntfsSavings();
            case ZIP_COPY, ZIP_REPLACE -> e.zipSavings();
            case TZST_COPY, TZST_REPLACE -> e.zstdSavings();
        };
        return v == null ? 0 : v;
    }

    /** Returns null when eligible, otherwise the reason the file is left untouched. */
    private String ineligibility(IndexedItem it, ActivityAssessment a, MonitoredRoot root, Method method,
                                 boolean viaFolder, Settings s) throws SQLException {
        if (!it.present()) return "Not present (already archived)";
        if (it.special()) return "Link, junction, cloud placeholder or special file — never modified";
        if (root == null || !root.active()) return "Folder authorization revoked";
        Protection prot = policy.protection(it.path(), false, it.logicalSize(), it.category(), it.volumeId(), root);
        if (prot.blocked()) return prot.reason();
        if (ProtectedRules.isIdleStowArchive(it.name())) return "IdleStow archive";
        if (it.optStatus() != OptStatus.NONE && it.optStatus() != OptStatus.NO_BENEFIT) {
            return "Already " + it.optStatus().label().toLowerCase(java.util.Locale.ROOT);
        }
        if (it.logicalSize() == 0) return "Empty file";
        EffectivePolicy eff = policy.effective(it.path(), it.extension(), root, s);
        if (eff.neverOptimize()) return "Policy: never optimize (" + eff.source() + ")";
        if (!eff.allowedMethods().contains(method)) return "Policy does not allow " + method.label();
        if (viaFolder) {
            switch (a.classification()) {
                case ACTIVE, PROBABLY_ACTIVE -> {
                    return "Active: " + a.explanation();
                }
                case UNKNOWN -> {
                    return "Activity unknown — select the file individually to optimize it anyway";
                }
                default -> { }
            }
            if (it.logicalSize() < s.minFileSize()) return "Smaller than the minimum file size";
        }
        if (method == Method.NATIVE) {
            VolumeInfo v = it.volumeId() == null ? null : roots.volume(it.volumeId()).orElse(null);
            if (v == null || !config.platform().nativeCompression().supportedOn(v)) {
                return "Native compression is not supported on this volume"
                        + (v == null ? "" : ": " + v.nativeCompressionNote());
            }
            if (it.nativeCompressed()) return "Already compressed";
            if (it.has(IndexedItem.ATTR_ENCRYPTED) || it.has(IndexedItem.ATTR_SPARSE)) return "Encrypted or sparse file";
        }
        if (method.replacesOriginal()) {
            if (it.linkCount() != 1) return "Has multiple hard links";
            if (it.altStreams() != 0) return "Has alternate data streams (or they cannot be checked)";
            if (policy.protectedRules().inCloudSyncFolder(it.path())) return "In a cloud-synchronized folder";
        }
        return null;
    }

    /**
     * Executes the user-approved subset of a preview: builds a plan, records a plan-bound approval, consumes it
     * and starts the job. Every file is re-validated again immediately before mutation.
     */
    public long execute(PlanPreview preview, List<Long> approvedItemIds, Origin origin, boolean replaceConfirmed)
            throws SQLException, IOException {
        requireWritable();
        if (preview.method().replacesOriginal() && !replaceConfirmed) {
            throw new IOException("Archive replacement requires explicit confirmation");
        }
        Settings s = settings.get();
        List<IndexedItem> selected = new ArrayList<>();
        List<PlanItem> planItems = new ArrayList<>();
        long est = 0;
        Set<Long> ok = Set.copyOf(approvedItemIds);
        List<String> skipped = new ArrayList<>();
        for (PlanPreview.Item pi : preview.willChange()) {
            if (!ok.contains(pi.itemId())) continue;
            IndexedItem it = items.byId(pi.itemId()).orElse(null);
            if (it == null) continue;
            // The approval covers exactly what was reviewed: a file that changed since then is not included.
            if (it.logicalSize() != pi.size() || it.modifiedMs() != pi.modifiedMs()
                    || !java.util.Objects.equals(it.fileKey(), pi.fileKey())) {
                skipped.add(it.name() + " changed since you reviewed it");
                continue;
            }
            MonitoredRoot root = roots.byId(it.rootId()).orElse(null);
            ActivityAssessment a = items.classification(it.id()).orElse(new ActivityAssessment(ActivityClass.UNKNOWN,
                    io.github.madaai.idlestow.core.model.Enums.Confidence.NONE, 0, -1, List.of("Not analyzed")));
            String why = ineligibility(it, a, root, preview.method(), false, s);
            if (why != null) {
                skipped.add(it.name() + ": " + why);
                continue;
            }
            selected.add(it);
            planItems.add(new PlanItem(it.id(), it.path().toString(), it.logicalSize(), it.modifiedMs(), it.fileKey()));
            est += pi.estimatedSavings();
        }
        if (selected.isEmpty()) {
            throw new IOException(skipped.isEmpty() ? "Nothing selected to optimize"
                    : "Nothing left to optimize: " + String.join("; ", skipped) + ". Re-open the preview.");
        }
        if (!skipped.isEmpty()) LOG.info("Excluded from the approved plan: {}", skipped);
        long planId = candidates.createPlan(preview.method(), origin.name(), s.mode().name(), policy.version(s),
                planItems, est);
        candidates.approve(planId, Duration.ofHours(24));
        String problem = candidates.consumeApproval(planId);
        if (!problem.isEmpty()) throw new IOException("Approval invalid: " + problem);
        long jobId = jobManager.submitOptimize(selected, preview.method(), origin, planId, null);
        fire(Event.JOBS);
        return jobId;
    }

    // =================================================================================== recommendations

    public List<Candidate> recommendations(List<CandidateStatus> statuses, Long rootId, String ext,
                                           CandidateRepository.Sort sort, int limit) throws SQLException {
        return candidates.list(statuses, rootId, ext, sort, limit);
    }

    /** Approves recommendations (Ask mode): groups by method, re-previews and executes each group. */
    public List<Long> approveRecommendations(List<Candidate> chosen, boolean replaceConfirmed)
            throws SQLException, IOException {
        requireWritable();
        Map<Method, List<Long>> byMethod = new LinkedHashMap<>();
        for (Candidate c : chosen) byMethod.computeIfAbsent(c.method(), k -> new ArrayList<>()).add(c.itemId());
        List<Long> jobIds = new ArrayList<>();
        for (Map.Entry<Method, List<Long>> e : byMethod.entrySet()) {
            PlanPreview p = preview(e.getValue(), e.getKey());
            List<Long> ids = p.willChange().stream().map(PlanPreview.Item::itemId).toList();
            if (ids.isEmpty()) continue;
            List<Long> candIds = chosen.stream().filter(c -> ids.contains(c.itemId())).map(Candidate::id).toList();
            candidates.setStatus(candIds, CandidateStatus.APPROVED, null);
            try {
                jobIds.add(execute(p, ids, Origin.APPROVAL, replaceConfirmed));
            } catch (IOException | SQLException | RuntimeException ex) {
                candidates.setStatus(candIds, CandidateStatus.PENDING, null);
                throw ex;
            }
        }
        fire(Event.ANALYSIS);
        return jobIds;
    }

    public void setRecommendationStatus(List<Candidate> chosen, CandidateStatus status, Duration postpone)
            throws SQLException {
        Long until = postpone == null ? null : config.clock().millis() + postpone.toMillis();
        candidates.setStatus(chosen.stream().map(Candidate::id).toList(), status, until);
        fire(Event.ANALYSIS);
    }

    // =================================================================================== recovery actions

    public long decompress(List<Long> itemIds) throws SQLException, IOException {
        requireWritable();
        List<IndexedItem> list = new ArrayList<>();
        for (Long id : itemIds) items.byId(id).filter(i -> i.nativeCompressed()).ifPresent(list::add);
        if (list.isEmpty()) throw new IOException("None of the selected files are natively compressed");
        return jobManager.submitDecompress(list, Origin.MANUAL);
    }

    public long restore(long recoveryId, Path destination) throws SQLException, IOException {
        requireWritable();
        RecoveryRepository.Record r = recovery.byId(recoveryId).orElseThrow(() -> new IOException("Unknown record"));
        if (r.kind() == RecoveryKind.NATIVE) {
            if (r.itemId() == null) throw new IOException("The file is no longer indexed");
            return decompress(List.of(r.itemId()));
        }
        return jobManager.submitRestore(r, destination);
    }

    public long reclaimRetained(List<Long> recoveryIds) throws SQLException, IOException {
        requireWritable();
        List<RecoveryRepository.Record> list = new ArrayList<>();
        for (Long id : recoveryIds) recovery.byId(id).ifPresent(list::add);
        return jobManager.submitPurge(list, Origin.MANUAL);
    }

    public long verifyRecovery(List<Long> recoveryIds) throws SQLException {
        List<RecoveryRepository.Record> list = new ArrayList<>();
        for (Long id : recoveryIds) recovery.byId(id).ifPresent(list::add);
        return jobManager.submitVerify(list);
    }

    public String deleteCompressedCopy(long recoveryId) throws SQLException {
        if (config.readOnly()) return "IdleStow is running in read-only mode; nothing was deleted";
        RecoveryRepository.Record r = recovery.byId(recoveryId).orElseThrow();
        var o = jobManager.executor().deleteCopy(0, r);
        fire(Event.RECOVERY);
        return o.message();
    }

    private void requireWritable() throws IOException {
        if (config.readOnly()) throw new IOException("IdleStow is running in read-only mode; no files are changed");
    }

    // =================================================================================== autonomy

    /** The exact policy the user confirms when enabling autonomy. */
    public record AutonomyPolicy(List<String> folders, List<String> methods, int inactivityDays, String minConfidence,
                                 int observationDays, int maxCpuPercent, boolean onBattery, boolean idleOnly,
                                 int maxBatch, String recoveryLimitations) { }

    public AutonomyPolicy currentAutonomyPolicy() throws SQLException {
        Settings s = settings.get();
        List<String> folders = roots.active().stream().filter(MonitoredRoot::autonomyEnabled)
                .map(r -> r.path().toString()).sorted().toList();
        return new AutonomyPolicy(folders, List.of(Method.NATIVE.name()), s.inactivityDays(), s.minConfidenceAuto().name(),
                s.observationDays(), s.maxCpuPercent(), s.autoOnBattery(), s.idleOnly(), s.maxBatchSize(),
                "Autonomous mode only uses transparent native compression, which is fully reversible."
                        + " Archive replacement is never performed autonomously.");
    }

    public static String digest(AutonomyPolicy p) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(Json.write(p).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Enables autonomy after the user explicitly confirmed exactly this policy. */
    public void confirmAutonomy(AutonomyPolicy confirmed) throws SQLException, IOException {
        AutonomyPolicy current = currentAutonomyPolicy();
        if (!current.equals(confirmed)) throw new IOException("The policy changed while you were reviewing it");
        if (current.folders().isEmpty()) throw new IOException("Enable autonomy for at least one monitored folder first");
        Settings s = settings.get().with("auth.mode", AuthorizationMode.AUTONOMOUS.name())
                .with("auth.autonomyDigest", digest(current));
        settings.save(s);
    }

    public void setMode(AuthorizationMode mode) throws SQLException {
        Settings s = settings.get().with("auth.mode", mode.name());
        if (mode != AuthorizationMode.AUTONOMOUS) s = s.with("auth.autonomyDigest", "");
        settings.save(s);
    }

    /** Autonomy runs only when the mode is autonomous AND the stored confirmation matches the current policy. */
    public boolean autonomyActive() {
        try {
            Settings s = settings.get();
            return s.mode() == AuthorizationMode.AUTONOMOUS && s.onboardingCompleted()
                    && !s.autonomyConfirmedDigest().isBlank()
                    && s.autonomyConfirmedDigest().equals(digest(currentAutonomyPolicy()));
        } catch (SQLException e) {
            return false;
        }
    }

    /** Returns null when autonomous work may proceed right now, else the reason it may not. */
    public String autonomyGate() {
        if (config.readOnly()) return "read-only mode";
        if (!autonomyActive()) return "autonomous mode is disabled or its policy changed since you confirmed it";
        Settings s = settings.get();
        if (!s.backgroundEnabled()) return "background operation is disabled";
        if (jobManager.globallyPaused() || config.clock().millis() < pausedUntilMs) return "paused by you";
        if (!s.autoOnBattery() && config.platform().powerStatus().onBattery().orElse(false)) return "running on battery";
        double cpu = systemCpuPercent();
        if (cpu >= 0 && cpu > s.maxCpuPercent()) return "the system is busy (CPU " + Math.round(cpu) + "%)";
        if (s.idleOnly()) {
            OptionalLong idle = config.platform().userIdleMillis();
            if (idle.isEmpty()) return "idle detection is unavailable on this platform";
            if (idle.getAsLong() < s.idleMinutes() * 60_000L) return "you are using the computer";
        }
        try {
            if (recovery.countNeedingAttention() > 0) return "a recovery issue needs your attention";
        } catch (SQLException e) {
            return "cannot check recovery state";
        }
        return null;
    }

    static double systemCpuPercent() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double l = sun.getCpuLoad();
            return l < 0 ? -1 : l * 100.0;
        }
        return -1;
    }

    /** One autonomous pass: optimizes auto-eligible recommendations within the confirmed policy. */
    public Optional<Long> runAutonomousPass() {
        String gate = autonomyGate();
        if (gate != null) {
            lastAutonomyMessage = "Autonomous optimization waiting: " + gate;
            fire(Event.STATUS);
            return Optional.empty();
        }
        if (jobManager.busy()) {
            lastAutonomyMessage = "Autonomous optimization waiting for running jobs";
            return Optional.empty();
        }
        try {
            Settings s = settings.get();
            List<Candidate> list = candidates.list(List.of(CandidateStatus.PENDING), null, null,
                    CandidateRepository.Sort.SAVINGS, s.maxBatchSize()).stream()
                    .filter(Candidate::autoEligible).filter(c -> c.method() == Method.NATIVE).toList();
            if (list.isEmpty()) {
                lastAutonomyMessage = "Autonomous optimization: nothing eligible right now";
                fire(Event.STATUS);
                return Optional.empty();
            }
            // free-space safeguard per volume
            PlanPreview p = preview(list.stream().map(Candidate::itemId).toList(), Method.NATIVE);
            List<IndexedItem> its = new ArrayList<>();
            List<PlanItem> planItems = new ArrayList<>();
            for (PlanPreview.Item pi : p.willChange()) {
                IndexedItem it = items.byId(pi.itemId()).orElse(null);
                if (it == null) continue;
                its.add(it);
                planItems.add(new PlanItem(it.id(), it.path().toString(), it.logicalSize(), it.modifiedMs(), it.fileKey()));
            }
            if (its.isEmpty()) return Optional.empty();
            long planId = candidates.createPlan(Method.NATIVE, Origin.AUTONOMOUS.name(), s.mode().name(),
                    policy.version(s), planItems, p.estimatedSavings());
            long jobId = jobManager.submitOptimize(its, Method.NATIVE, Origin.AUTONOMOUS, planId,
                    this::autonomyGate, this::autonomousItemCheck);
            lastAutonomyMessage = "Autonomous optimization started for " + its.size() + " files (estimated "
                    + Sizes.format(p.estimatedSavings()) + ")";
            fire(Event.STATUS);
            return Optional.of(jobId);
        } catch (SQLException e) {
            LOG.error("Autonomous pass failed", e);
            lastAutonomyMessage = "Autonomous optimization failed: " + e.getMessage();
            return Optional.empty();
        }
    }

    /**
     * Re-checks one file immediately before an autonomous mutation: refreshes its metadata from disk and
     * re-runs the activity rules, so a file used since the last analysis is skipped.
     */
    JobManager.ItemCheck autonomousItemCheck(IndexedItem it) {
        try {
            MonitoredRoot root = roots.byId(it.rootId()).orElse(null);
            if (root == null || !root.active() || !root.autonomyEnabled()) {
                return new JobManager.ItemCheck(null, "autonomy is no longer enabled for this folder");
            }
            scanner.scan(root, it.path(), new CancellationToken(), p -> { });
            IndexedItem fresh = items.byId(it.id()).orElse(null);
            if (fresh == null || !fresh.present()) return new JobManager.ItemCheck(null, "the file no longer exists");
            Settings s = settings.get();
            if (fresh.optStatus() != OptStatus.NONE) return new JobManager.ItemCheck(null, "already processed");
            if (fresh.logicalSize() < s.minFileSize()) return new JobManager.ItemCheck(null, "below the minimum size");
            ActivityAssessment a = analysis.assess(fresh, root, s);
            EffectivePolicy eff = policy.effective(fresh.path(), fresh.extension(), root, s);
            if (a.classification() != ActivityClass.INACTIVE || !a.confidence().atLeast(eff.minConfidenceAuto())
                    || eff.neverOptimize()) {
                return new JobManager.ItemCheck(null, "no longer inactive: " + a.explanation());
            }
            return new JobManager.ItemCheck(fresh, null);
        } catch (SQLException | IOException | RuntimeException e) {
            return new JobManager.ItemCheck(null, "cannot re-check the file (" + e.getMessage() + ")");
        }
    }

    /** Pauses all optimization (and background scanning) for {@code d}, or indefinitely when null. */
    public void pauseAll(Duration d) {
        jobManager.setGloballyPaused(true);
        pausedUntilMs = d == null ? Long.MAX_VALUE : config.clock().millis() + d.toMillis();
        if (d != null) scheduler.schedule(this::resumeAll, d.toMillis(), TimeUnit.MILLISECONDS);
        fire(Event.STATUS);
    }

    public void resumeAll() {
        jobManager.setGloballyPaused(false);
        pausedUntilMs = 0;
        fire(Event.STATUS);
    }

    public boolean paused() {
        return jobManager.globallyPaused();
    }

    // =================================================================================== scheduling

    private synchronized void reschedule() {
        for (ScheduledFuture<?> f : scheduled) f.cancel(false);
        scheduled.clear();
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
        Settings s = settings.get();
        if (!s.backgroundEnabled()) {
            nextScanAtMs = 0;
            return;
        }
        long scanMs = s.scanIntervalHours() * 3_600_000L;
        nextScanAtMs = config.clock().millis() + scanMs;
        scheduled.add(scheduler.scheduleWithFixedDelay(() -> {
            if (!paused()) scan(null);
            nextScanAtMs = config.clock().millis() + scanMs;
        }, scanMs, scanMs, TimeUnit.MILLISECONDS));
        long autoMs = s.autoIntervalHours() * 3_600_000L;
        scheduled.add(scheduler.scheduleWithFixedDelay(() -> {
            if (autonomyActive()) runAutonomousPass();
        }, Math.min(autoMs, 15 * 60_000L), autoMs, TimeUnit.MILLISECONDS));
        if (s.watchEnabled()) {
            try {
                FileWatcher w = new FileWatcher(this::onChanges, 3000);
                for (MonitoredRoot r : roots.active()) w.watch(r.id(), r.path());
                watcher = w;
            } catch (IOException | SQLException e) {
                LOG.warn("Filesystem monitoring unavailable: {}", e.toString());
            }
        }
    }

    // =================================================================================== misc

    public long pendingRecommendationCount() throws SQLException {
        return candidates.pendingCount();
    }

    public long pendingSavings() throws SQLException {
        return candidates.pendingSavings();
    }

    /** Exports user-facing data (settings, roots, rules, history, recovery records) as JSON. */
    public void exportData(Path target) throws SQLException, IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exported_utc", config.clock().instant().toString());
        out.put("settings", settings.get().toMap());
        out.put("roots", roots.all().stream().map(r -> Json.map("path", r.path().toString(), "autonomy",
                r.autonomyEnabled(), "archival", r.archival(), "revoked", r.revokedUtc())).toList());
        out.put("exclusions", rules.exclusions());
        out.put("pins", rules.pins().stream().map(p -> p.path().toString()).toList());
        out.put("policies", rules.policies());
        out.put("history", history.search(null, null, false, 0, 100_000));
        out.put("recovery_records", recovery.list(true, 100_000));
        Files.writeString(target, Json.pretty(out), StandardCharsets.UTF_8);
    }

    /**
     * Clears the file index (optional local data). Recovery records, history, settings and authorized folders
     * are kept so nothing that is needed for restoration is lost.
     */
    public void clearIndex() throws SQLException {
        db.tx(c -> {
            io.github.madaai.idlestow.core.db.Sql.update(c, "DELETE FROM optimization_candidates");
            io.github.madaai.idlestow.core.db.Sql.update(c, "DELETE FROM directory_aggregates");
            io.github.madaai.idlestow.core.db.Sql.update(c,
                    "DELETE FROM indexed_items WHERE opt_status NOT IN ('ARCHIVED','NATIVE_COMPRESSED','ARCHIVE_COPY','RECOVERY_REQUIRED')");
        });
        fire(Event.ANALYSIS);
    }

    public ActivityClassifier classifier() {
        return new ActivityClassifier();
    }

    @Override
    public void close() {
        for (ScheduledFuture<?> f : scheduled) f.cancel(false);
        scheduler.shutdownNow();
        if (watcher != null) watcher.close();
        scanToken.cancel();
        scanExecutor.shutdownNow();
        jobManager.close();
        try {
            config.worker().close();
        } catch (RuntimeException e) {
            LOG.debug("Worker close failed", e);
        }
        db.close();
    }
}
