package io.github.madaai.idlestow.core.util;

import java.util.concurrent.CancellationException;

/** Cooperative cancellation and pause control checked at safe points by long-running work. */
public final class CancellationToken {
    private volatile boolean cancelled;
    private volatile boolean paused;
    private final Object pauseLock = new Object();

    public void cancel() {
        cancelled = true;
        resume();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    /** Blocks while paused; throws {@link CancellationException} when cancelled. */
    public void checkpoint() {
        if (cancelled) throw new CancellationException("Cancelled");
        if (paused) {
            synchronized (pauseLock) {
                while (paused && !cancelled) {
                    try {
                        pauseLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Interrupted");
                    }
                }
            }
            if (cancelled) throw new CancellationException("Cancelled");
        }
    }
}
