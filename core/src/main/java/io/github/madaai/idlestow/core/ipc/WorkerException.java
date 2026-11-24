package io.github.madaai.idlestow.core.ipc;

import java.io.IOException;

/** Structured error from the Python worker (or the host side of the protocol). */
public class WorkerException extends IOException {
    private final String code;
    private final boolean retryable;

    public WorkerException(String code, String message, boolean retryable) {
        super(code + ": " + message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public static final String WORKER_UNAVAILABLE = "WORKER_UNAVAILABLE";
    public static final String WORKER_CRASHED = "WORKER_CRASHED";
    public static final String TIMEOUT = "TIMEOUT";
}
