package io.github.madaai.idlestow.core.jobs;

import io.github.madaai.idlestow.core.db.RecoveryRepository;
import io.github.madaai.idlestow.core.ipc.WorkerClient.ProgressListener;
import io.github.madaai.idlestow.core.model.Enums.JobKind;
import io.github.madaai.idlestow.core.model.Enums.JobState;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.Origin;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.util.CancellationToken;
import io.github.madaai.idlestow.core.util.PathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs durable jobs on a small bounded thread pool.
 *
 * <p>Cancellation and pausing are honoured between files (a safe transaction boundary) and inside
 * the worker's chunked streaming before any commit. A file is never mutated by two operations at
 * once (per-path locks). Autonomous jobs re-check their authorization gate before every file so a
 * revocation stops them at the next safe point.
 */
public final class JobManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(JobManager.class);

    private final JobContext ctx;
    private final OperationExecutor executor;
    private final ExecutorService pool;
    private final Map<Long, CancellationToken> tokens = new ConcurrentHashMap<>();
    private final Map<Long, Future<?>> running = new ConcurrentHashMap<>();
    private final Set<String> lockedPaths = ConcurrentHashMap.newKeySet();
    private final List<Consumer<Long>> listeners = new CopyOnWriteArrayList<>();
    private final Map<Long, LiveProgress> live = new ConcurrentHashMap<>();
    private volatile boolean globallyPaused;

    /** Live, in-memory progress of the current file of a job (bytes processed / throughput). */
    public record LiveProgress(String currentPath, long fileDone, long fileTotal, String stage, long startedNanos,
                               long bytesBefore) { }

    public JobManager(JobContext ctx, int concurrency) {
        this.ctx = ctx;
        this.executor = new OperationExecutor(ctx);
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(Math.max(1, Math.min(4, concurrency)), r -> {
            Thread t = new Thread(r, "idlestow-job-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    public OperationExecutor executor() {
        return executor;
    }

    public void addListener(Consumer<Long> l) {
        listeners.add(l);
    }

    private void fire(long jobId) {
        for (Consumer<Long> l : listeners) {
            try {
                l.accept(jobId);
            } catch (RuntimeException e) {
                LOG.debug("Job listener failed", e);
            }
        }
    }

    public Optional<LiveProgress> live(long jobId) {
        return Optional.ofNullable(live.get(jobId));
    }

    // ------------------------------------------------------------------ submission

    /** Optimizes the given items. {@code gate} returns null to continue or a reason to stop (autonomy checks). */
    public long submitOptimize(List<IndexedItem> items, Method method, Origin origin, Long planId,
                               Supplier<String> gate) throws SQLException {
        long total = items.stream().mapToLong(IndexedItem::logicalSize).sum();
        long jobId = ctx.jobs().createJob(JobKind.OPTIMIZE, method, origin, planId, items.size(), total);
        start(jobId, token -> {
            int done = 0, failed = 0;
            long bytes = 0;
            List<String> messages = new ArrayList<>();
            for (IndexedItem it : items) {
                String stop = gate == null ? null : gate.get();
                if (stop != null) {
                    messages.add("Stopped: " + stop);
                    ctx.jobs().setState(jobId, JobState.CANCELLED, "Stopped before " + it.name() + ": " + stop);
                    return;
                }
                waitWhilePaused(jobId, token);
                if (token.isCancelled()) break;
                OpOutcome o = withLock(it.path(), () -> executor.optimize(jobId, it, method, origin, token,
                        progressFor(jobId, it.path(), it.logicalSize())));
                if (o.result() == OpOutcome.Result.SUCCEEDED || o.result() == OpOutcome.Result.NO_BENEFIT) done++;
                else if (o.result() != OpOutcome.Result.CANCELLED) {
                    failed++;
                    messages.add(it.name() + ": " + o.message());
                }
                bytes += o.bytesProcessed();
                ctx.jobs().progress(jobId, done, failed, bytes);
                fire(jobId);
            }
            finish(jobId, token, done, failed, messages);
        });
        return jobId;
    }

    public long submitDecompress(List<IndexedItem> items, Origin origin) throws SQLException {
        long total = items.stream().mapToLong(IndexedItem::logicalSize).sum();
        long jobId = ctx.jobs().createJob(JobKind.DECOMPRESS, Method.NATIVE, origin, null, items.size(), total);
        start(jobId, token -> {
            int done = 0, failed = 0;
            long bytes = 0;
            List<String> messages = new ArrayList<>();
            for (IndexedItem it : items) {
                waitWhilePaused(jobId, token);
                if (token.isCancelled()) break;
                OpOutcome o = withLock(it.path(), () -> executor.decompress(jobId, it, origin, token,
                        progressFor(jobId, it.path(), it.logicalSize())));
                if (o.result() == OpOutcome.Result.SUCCEEDED) done++;
                else if (o.result() != OpOutcome.Result.CANCELLED) {
                    failed++;
                    messages.add(it.name() + ": " + o.message());
                }
                bytes += o.bytesProcessed();
                ctx.jobs().progress(jobId, done, failed, bytes);
                fire(jobId);
            }
            finish(jobId, token, done, failed, messages);
        });
        return jobId;
    }

    /** Restores one archived file. Returns the job id; the outcome (including conflicts) is in the job message. */
    public long submitRestore(RecoveryRepository.Record rec, Path destination) throws SQLException {
        long jobId = ctx.jobs().createJob(JobKind.RESTORE, null, Origin.MANUAL, null, 1,
                rec.size() == null ? 0 : rec.size());
        Path lockPath = destination != null ? destination : Path.of(rec.originalPath());
        start(jobId, token -> {
            OpOutcome o = withLock(lockPath, () -> executor.restore(jobId, rec, destination, token,
                    progressFor(jobId, lockPath, rec.size() == null ? 0 : rec.size())));
            boolean ok = o.result() == OpOutcome.Result.SUCCEEDED;
            ctx.jobs().progress(jobId, ok ? 1 : 0, ok ? 0 : 1, o.bytesProcessed());
            JobState st = switch (o.result()) {
                case SUCCEEDED -> JobState.RESTORED;
                case CANCELLED -> JobState.CANCELLED;
                case RECOVERY_REQUIRED -> JobState.RECOVERY_REQUIRED;
                default -> JobState.FAILED;
            };
            ctx.jobs().setState(jobId, st, o.message());
        });
        return jobId;
    }

    public long submitPurge(List<RecoveryRepository.Record> recs, Origin origin) throws SQLException {
        long jobId = ctx.jobs().createJob(JobKind.PURGE_RETAINED, null, origin, null, recs.size(), 0);
        start(jobId, token -> {
            int done = 0, failed = 0;
            long bytes = 0;
            List<String> messages = new ArrayList<>();
            for (RecoveryRepository.Record r : recs) {
                waitWhilePaused(jobId, token);
                if (token.isCancelled()) break;
                OpOutcome o = withLock(Path.of(r.originalPath()), () -> executor.purgeRetained(jobId, r, token,
                        progressFor(jobId, Path.of(r.originalPath()), r.size() == null ? 0 : r.size())));
                if (o.result() == OpOutcome.Result.SUCCEEDED) {
                    done++;
                    bytes += o.savings();
                } else if (o.result() != OpOutcome.Result.CANCELLED) {
                    failed++;
                    messages.add(Path.of(r.originalPath()).getFileName() + ": " + o.message());
                }
                ctx.jobs().progress(jobId, done, failed, bytes);
                fire(jobId);
            }
            finish(jobId, token, done, failed, messages);
        });
        return jobId;
    }

    public long submitVerify(List<RecoveryRepository.Record> recs) throws SQLException {
        long jobId = ctx.jobs().createJob(JobKind.VERIFY, null, Origin.MANUAL, null, recs.size(), 0);
        start(jobId, token -> {
            int done = 0, failed = 0;
            List<String> messages = new ArrayList<>();
            for (RecoveryRepository.Record r : recs) {
                if (token.isCancelled()) break;
                OpOutcome o = executor.verifyRecord(r, token,
                        progressFor(jobId, Path.of(r.originalPath()), r.size() == null ? 0 : r.size()));
                if (o.result() == OpOutcome.Result.SUCCEEDED) done++;
                else {
                    failed++;
                    messages.add(Path.of(r.originalPath()).getFileName() + ": " + o.message());
                }
                ctx.jobs().progress(jobId, done, failed, 0);
                fire(jobId);
            }
            finish(jobId, token, done, failed, messages);
        });
        return jobId;
    }

    // ------------------------------------------------------------------ control

    public void cancel(long jobId) throws SQLException {
        ctx.jobs().requestCancel(jobId);
        CancellationToken t = tokens.get(jobId);
        if (t != null) {
            t.cancel();
            ctx.jobs().setState(jobId, currentState(jobId), "Stopping after the current safe step…");
        } else {
            ctx.jobs().job(jobId).ifPresent(j -> {
                if (!j.state().terminal()) {
                    try {
                        ctx.jobs().setState(jobId, JobState.CANCELLED, "Cancelled");
                    } catch (SQLException e) {
                        LOG.warn("Cannot cancel job", e);
                    }
                }
            });
        }
        fire(jobId);
    }

    private JobState currentState(long jobId) throws SQLException {
        return ctx.jobs().job(jobId).map(j -> j.state()).orElse(JobState.CANCELLED);
    }

    public void pause(long jobId) throws SQLException {
        CancellationToken t = tokens.get(jobId);
        if (t != null) {
            t.pause();
            ctx.jobs().requestPause(jobId, true);
            ctx.jobs().setState(jobId, JobState.PAUSED, "Paused (will pause after the current file)");
            fire(jobId);
        }
    }

    public void resume(long jobId) throws SQLException {
        CancellationToken t = tokens.get(jobId);
        if (t != null) {
            ctx.jobs().requestPause(jobId, false);
            ctx.jobs().setState(jobId, JobState.RUNNING, "Running");
            t.resume();
            fire(jobId);
        }
    }

    /** Global pause of all optimization work (between files). */
    public void setGloballyPaused(boolean paused) {
        globallyPaused = paused;
        for (CancellationToken t : tokens.values()) {
            if (paused) t.pause();
            else t.resume();
        }
    }

    public boolean globallyPaused() {
        return globallyPaused;
    }

    public boolean busy() {
        return !running.isEmpty();
    }

    // ------------------------------------------------------------------ internals

    @FunctionalInterface
    private interface JobBody {
        void run(CancellationToken token) throws SQLException;
    }

    private void start(long jobId, JobBody body) {
        CancellationToken token = new CancellationToken();
        if (globallyPaused) token.pause();
        tokens.put(jobId, token);
        Future<?> f = pool.submit(() -> {
            try {
                ctx.jobs().setState(jobId, JobState.RUNNING, "Running");
                fire(jobId);
                body.run(token);
            } catch (SQLException e) {
                LOG.error("Job {} failed with a database error", jobId, e);
                try {
                    ctx.jobs().setState(jobId, JobState.FAILED, "Database error: " + e.getMessage());
                } catch (SQLException ignored) {
                    // database unusable
                }
            } catch (RuntimeException e) {
                LOG.error("Job {} failed unexpectedly", jobId, e);
                try {
                    ctx.jobs().setState(jobId, JobState.FAILED, "Unexpected error: " + e);
                } catch (SQLException ignored) {
                    // database unusable
                }
            } finally {
                tokens.remove(jobId);
                running.remove(jobId);
                live.remove(jobId);
                fire(jobId);
            }
        });
        running.put(jobId, f);
    }

    private void waitWhilePaused(long jobId, CancellationToken token) throws SQLException {
        if (token.isPaused()) {
            ctx.jobs().setState(jobId, JobState.PAUSED, "Paused");
            fire(jobId);
            try {
                token.checkpoint();
            } catch (java.util.concurrent.CancellationException e) {
                return;
            }
            ctx.jobs().setState(jobId, JobState.RUNNING, "Running");
            fire(jobId);
        }
    }

    private void finish(long jobId, CancellationToken token, int done, int failed, List<String> messages)
            throws SQLException {
        String summary = done + " succeeded" + (failed > 0 ? ", " + failed + " failed" : "")
                + (messages.isEmpty() ? "" : ". " + String.join("; ", messages.subList(0, Math.min(5, messages.size()))));
        JobState st = token.isCancelled() ? JobState.CANCELLED : (failed > 0 && done == 0 ? JobState.FAILED
                : JobState.COMPLETED);
        ctx.jobs().setState(jobId, st, summary);
    }

    private OpOutcome withLock(Path p, Supplier<OpOutcome> op) {
        String key = PathUtil.key(p);
        if (!lockedPaths.add(key)) return OpOutcome.skipped("Another operation is already working on this file");
        try {
            return op.get();
        } finally {
            lockedPaths.remove(key);
        }
    }

    private ProgressListener progressFor(long jobId, Path p, long total) {
        long started = System.nanoTime();
        return (done, tot, stage) -> {
            live.put(jobId, new LiveProgress(p.toString(), done, tot > 0 ? tot : total, stage, started, 0));
            fire(jobId);
        };
    }

    @Override
    public void close() {
        for (CancellationToken t : tokens.values()) t.cancel();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.warn("Jobs did not stop within 15 s; remaining state will be recovered at next start");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
