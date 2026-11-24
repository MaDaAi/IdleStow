package io.github.madaai.idlestow.app.util;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Runs blocking work (engine calls, file IO) on a small bounded pool and delivers results on the JavaFX
 * application thread. Nothing that touches SQLite or the file system may run on the FX thread.
 */
public final class Background {
    private static final Logger LOG = LoggerFactory.getLogger(Background.class);
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(3, 6, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2000), r -> {
                Thread t = new Thread(r, "idlestow-ui-bg-" + COUNTER.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    static {
        POOL.allowCoreThreadTimeOut(true);
    }

    private Background() { }

    /** Blocking work that may throw checked exceptions. */
    @FunctionalInterface
    public interface Work<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    public interface Job {
        void run() throws Exception;
    }

    /** Executes {@code work} off the FX thread; the returned future completes on the FX thread. */
    public static <T> CompletableFuture<T> supply(Work<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            POOL.execute(() -> {
                try {
                    T r = work.call();
                    fx(() -> f.complete(r));
                } catch (Throwable t) {
                    fx(() -> f.completeExceptionally(t));
                }
            });
        } catch (RejectedExecutionException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    public static CompletableFuture<Void> run(Job job) {
        return supply(() -> {
            job.run();
            return null;
        });
    }

    /** Convenience: run, then deliver either the result or a readable error on the FX thread. */
    public static <T> void call(Work<T> work, Consumer<T> ok, Consumer<String> error) {
        supply(work).whenComplete((r, t) -> {
            if (t != null) {
                LOG.debug("Background task failed", t);
                if (error != null) error.accept(message(t));
            } else if (ok != null) {
                ok.accept(r);
            }
        });
    }

    /** Fire-and-forget background work; failures are logged. */
    public static void fireAndForget(Job job) {
        run(job).exceptionally(t -> {
            LOG.warn("Background task failed: {}", message(t));
            return null;
        });
    }

    public static void fx(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            try {
                Platform.runLater(r);
            } catch (IllegalStateException e) {
                LOG.debug("FX toolkit not available, dropping UI update");
            }
        }
    }

    /** Human readable text for an exception (unwraps completion wrappers, never returns null). */
    public static String message(Throwable t) {
        Throwable c = t;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }
}
