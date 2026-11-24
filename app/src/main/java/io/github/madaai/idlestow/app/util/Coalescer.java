package io.github.madaai.idlestow.app.util;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns bursts of change notifications (which arrive on engine threads) into at most one UI refresh per
 * interval, executed through {@code dispatcher} (the FX thread in production).
 */
public final class Coalescer {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "idlestow-coalescer");
        t.setDaemon(true);
        return t;
    });

    private final long intervalMs;
    private final Executor dispatcher;
    private final Runnable action;
    private final AtomicBoolean pending = new AtomicBoolean();

    public Coalescer(long intervalMs, Executor dispatcher, Runnable action) {
        this.intervalMs = Math.max(0, intervalMs);
        this.dispatcher = dispatcher;
        this.action = action;
    }

    /** Thread-safe. Requests that arrive while one is pending are merged into it. */
    public void request() {
        if (pending.compareAndSet(false, true)) {
            TIMER.schedule(() -> dispatcher.execute(() -> {
                pending.set(false);
                action.run();
            }), intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    public boolean isPending() {
        return pending.get();
    }
}
