package io.github.madaai.idlestow.core.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Host side of the worker protocol (docs/architecture/ipc-protocol.md).
 *
 * <p>Manages one persistent Python process: handshake, request/response correlation, progress
 * routing, cancellation, inactivity timeouts, heartbeats, bounded automatic restarts and graceful
 * shutdown. Diagnostics from the worker's stderr go to the log, never into the protocol stream.
 */
public final class WorkerClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerClient.class);
    private static final Logger WORKER_LOG = LoggerFactory.getLogger("idlestow.worker");

    static final int MAX_LINE = 1024 * 1024;
    static final int PROTOCOL = 1;
    private static final int MAX_RESTARTS = 3;
    private static final long RESTART_WINDOW_MS = 5 * 60_000L;

    private final WorkerLauncher launcher;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final Deque<Long> restartTimes = new ArrayDeque<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "idlestow-worker-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private volatile Process process;
    private volatile OutputStream stdin;
    private volatile JsonNode capabilities;
    private volatile CompletableFuture<JsonNode> ready;
    private volatile boolean closed;
    private volatile String lastError = "";
    private final Object writeLock = new Object();
    private final Object lifecycle = new Object();

    /** Listener for progress messages: (done, total, stage). */
    public interface ProgressListener {
        void onProgress(long done, long total, String stage);

        ProgressListener NONE = (d, t, s) -> { };
    }

    private record Pending(CompletableFuture<JsonNode> future, ProgressListener listener, AtomicLong lastActivity) { }

    public WorkerClient(WorkerLauncher launcher) {
        this.launcher = launcher;
        heartbeat.scheduleWithFixedDelay(this::heartbeatTick, 30, 30, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the worker if needed and waits for its handshake. */
    public JsonNode ensureStarted() throws WorkerException {
        synchronized (lifecycle) {
            if (closed) throw new WorkerException(WorkerException.WORKER_UNAVAILABLE, "Worker client is closed", false);
            Process p = process;
            if (p != null && p.isAlive() && ready != null && ready.isDone() && !ready.isCompletedExceptionally()) {
                return capabilities;
            }
            if (p != null && !p.isAlive()) {
                if (!allowRestart()) {
                    throw new WorkerException(WorkerException.WORKER_UNAVAILABLE,
                            "The Python worker crashed repeatedly and was not restarted. Last error: " + lastError, false);
                }
                LOG.warn("Restarting Python worker (previous exit code {})", p.exitValue());
            }
            start();
        }
        try {
            JsonNode hello = ready.get(20, TimeUnit.SECONDS);
            capabilities = hello.path("capabilities");
            return capabilities;
        } catch (TimeoutException e) {
            destroy();
            throw new WorkerException(WorkerException.TIMEOUT, "Worker did not complete its handshake", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerException(WorkerException.WORKER_UNAVAILABLE, "Interrupted", true);
        } catch (ExecutionException e) {
            destroy();
            throw new WorkerException(WorkerException.WORKER_UNAVAILABLE,
                    "Worker failed to start: " + e.getCause().getMessage(), true);
        }
    }

    private boolean allowRestart() {
        long now = System.currentTimeMillis();
        while (!restartTimes.isEmpty() && now - restartTimes.peekFirst() > RESTART_WINDOW_MS) restartTimes.pollFirst();
        if (restartTimes.size() >= MAX_RESTARTS) return false;
        restartTimes.addLast(now);
        return true;
    }

    private void start() throws WorkerException {
        ProcessBuilder pb = new ProcessBuilder(launcher.command());
        pb.directory(launcher.workingDir().toFile());
        Map<String, String> env = pb.environment();
        env.put("PYTHONPATH", launcher.pythonPath());
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUTF8", "1");
        env.remove("PYTHONHOME");
        env.remove("PYTHONSTARTUP");
        pb.redirectErrorStream(false);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            lastError = e.getMessage();
            throw new WorkerException(WorkerException.WORKER_UNAVAILABLE,
                    "Cannot start Python worker (" + launcher.command().get(0) + "): " + e.getMessage(), false);
        }
        process = p;
        stdin = p.getOutputStream();
        ready = new CompletableFuture<>();
        Thread out = new Thread(() -> readLoop(p, p.getInputStream()), "idlestow-worker-stdout");
        out.setDaemon(true);
        out.start();
        Thread err = new Thread(() -> drainStderr(p.getErrorStream()), "idlestow-worker-stderr");
        err.setDaemon(true);
        err.start();
        p.onExit().thenAccept(this::onExit);
        LOG.info("Started Python worker pid {} ({})", p.pid(), launcher.bundled() ? "bundled" : "development");
    }

    private void onExit(Process p) {
        if (p != process) return;
        String msg = "Python worker exited with code " + p.exitValue();
        if (!closed) LOG.warn(msg);
        lastError = msg;
        CompletableFuture<JsonNode> r = ready;
        if (r != null && !r.isDone()) r.completeExceptionally(new IOException(msg));
        failAll(new WorkerException(WorkerException.WORKER_CRASHED, msg, true));
    }

    private void failAll(WorkerException e) {
        for (Map.Entry<String, Pending> en : pending.entrySet()) {
            if (pending.remove(en.getKey(), en.getValue())) en.getValue().future().completeExceptionally(e);
        }
    }

    // ------------------------------------------------------------------ I/O

    private void readLoop(Process p, InputStream in) {
        try {
            ByteArrayOutputStream line = new ByteArrayOutputStream(256);
            boolean overflow = false;
            int b;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                for (int i = 0; i < n; i++) {
                    b = buf[i];
                    if (b == '\n') {
                        if (!overflow) handleLine(line.toString(StandardCharsets.UTF_8));
                        else LOG.warn("Discarded oversized message from worker");
                        line.reset();
                        overflow = false;
                    } else if (!overflow) {
                        if (line.size() >= MAX_LINE) overflow = true;
                        else line.write(b);
                    }
                }
            }
        } catch (IOException e) {
            if (!closed) LOG.debug("Worker stdout closed: {}", e.toString());
        }
    }

    private void handleLine(String text) {
        if (text.isBlank()) return;
        JsonNode msg;
        try {
            msg = json.readTree(text);
        } catch (IOException e) {
            LOG.warn("Ignoring malformed message from worker");
            return;
        }
        if (msg.path("v").asInt(-1) != PROTOCOL) {
            LOG.warn("Ignoring worker message with unsupported protocol version");
            return;
        }
        String type = msg.path("type").asText("");
        if (type.equals("ready")) {
            CompletableFuture<JsonNode> r = ready;
            if (r != null) r.complete(msg);
            return;
        }
        String id = msg.path("id").isTextual() ? msg.get("id").asText() : null;
        if (id == null) {
            LOG.warn("Worker reported an error without request id: {}", msg.path("error").path("message").asText());
            return;
        }
        Pending pd = pending.get(id);
        if (pd == null) return;
        pd.lastActivity().set(System.currentTimeMillis());
        switch (type) {
            case "progress" -> pd.listener().onProgress(msg.path("done").asLong(), msg.path("total").asLong(),
                    msg.path("stage").asText(""));
            case "result" -> {
                if (pending.remove(id, pd)) pd.future().complete(msg.path("result"));
            }
            case "error" -> {
                if (pending.remove(id, pd)) {
                    JsonNode e = msg.path("error");
                    pd.future().completeExceptionally(new WorkerException(e.path("code").asText("INTERNAL"),
                            e.path("message").asText(""), e.path("retryable").asBoolean(false)));
                }
            }
            default -> LOG.debug("Unknown worker message type {}", type);
        }
    }

    private void drainStderr(InputStream err) {
        try {
            byte[] buf = new byte[4096];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = err.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                int nl;
                while ((nl = sb.indexOf("\n")) >= 0) {
                    String l = sb.substring(0, Math.min(nl, 2000));
                    sb.delete(0, nl + 1);
                    WORKER_LOG.info(l.stripTrailing());
                }
                if (sb.length() > 64_000) sb.setLength(0);
            }
        } catch (IOException ignored) {
            // stream closed when the worker exits
        }
    }

    private void send(ObjectNode msg) throws WorkerException {
        byte[] bytes;
        try {
            bytes = (json.writeValueAsString(msg) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WorkerException("BAD_REQUEST", "Cannot encode request", false);
        }
        if (bytes.length > MAX_LINE) throw new WorkerException("MESSAGE_TOO_LARGE", "Request exceeds 1 MiB", false);
        synchronized (writeLock) {
            try {
                OutputStream o = stdin;
                if (o == null) throw new IOException("worker not started");
                o.write(bytes);
                o.flush();
            } catch (IOException e) {
                throw new WorkerException(WorkerException.WORKER_CRASHED, "Cannot write to worker: " + e.getMessage(), true);
            }
        }
    }

    // ------------------------------------------------------------------ requests

    /** Handle to an in-flight request that can be cancelled. */
    public final class Call {
        private final String id;
        private final CompletableFuture<JsonNode> future;
        private final AtomicLong lastActivity;

        Call(String id, CompletableFuture<JsonNode> future, AtomicLong lastActivity) {
            this.id = id;
            this.future = future;
            this.lastActivity = lastActivity;
        }

        public String id() { return id; }

        /** Asks the worker to cancel; the call then fails with code CANCELLED. */
        public void cancel() {
            ObjectNode m = envelope(nextId(), "cancel");
            m.putObject("params").put("target", id);
            try {
                send(m);
            } catch (WorkerException e) {
                LOG.debug("Cancel not delivered: {}", e.getMessage());
            }
        }

        /**
         * Waits for the result. Fails with TIMEOUT when no progress or result arrives for {@code inactivity}.
         */
        public JsonNode await(Duration inactivity) throws WorkerException {
            while (true) {
                try {
                    return future.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    if (System.currentTimeMillis() - lastActivity.get() > inactivity.toMillis()) {
                        pending.remove(id);
                        cancel();
                        throw new WorkerException(WorkerException.TIMEOUT,
                                "No response from worker for " + inactivity.toSeconds() + " s", true);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancel();
                    throw new WorkerException("CANCELLED", "Interrupted", true);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof WorkerException we) throw we;
                    throw new WorkerException("INTERNAL", String.valueOf(e.getCause()), false);
                }
            }
        }
    }

    private String nextId() {
        return "r-" + ids.incrementAndGet();
    }

    private ObjectNode envelope(String id, String op) {
        ObjectNode m = json.createObjectNode();
        m.put("v", PROTOCOL);
        m.put("id", id);
        m.put("op", op);
        return m;
    }

    /** Sends a request and returns a handle. {@code params} may be null. */
    public Call submit(String op, ObjectNode params, ProgressListener listener) throws WorkerException {
        ensureStarted();
        String id = nextId();
        ObjectNode m = envelope(id, op);
        m.set("params", params == null ? json.createObjectNode() : params);
        AtomicLong last = new AtomicLong(System.currentTimeMillis());
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, new Pending(f, listener == null ? ProgressListener.NONE : listener, last));
        try {
            send(m);
        } catch (WorkerException e) {
            pending.remove(id);
            throw e;
        }
        return new Call(id, f, last);
    }

    /** Convenience: submit and wait with an inactivity timeout. */
    public JsonNode request(String op, ObjectNode params, ProgressListener listener, Duration inactivity)
            throws WorkerException {
        return submit(op, params, listener).await(inactivity);
    }

    public ObjectNode params() {
        return json.createObjectNode();
    }

    public ObjectMapper json() {
        return json;
    }

    public JsonNode capabilities() throws WorkerException {
        return ensureStarted();
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    public String status() {
        Process p = process;
        if (closed) return "Stopped";
        if (p == null) return "Not started";
        return p.isAlive() ? "Running (pid " + p.pid() + ")" : "Exited: " + lastError;
    }

    private void heartbeatTick() {
        if (closed || !isRunning() || !pending.isEmpty()) return;
        try {
            request("ping", null, null, Duration.ofSeconds(15));
        } catch (WorkerException e) {
            LOG.warn("Worker heartbeat failed ({}); restarting", e.getMessage());
            destroy();
        }
    }

    private void destroy() {
        Process p = process;
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    @Override
    public void close() {
        closed = true;
        heartbeat.shutdownNow();
        Process p = process;
        if (p != null && p.isAlive()) {
            try {
                ObjectNode m = envelope(nextId(), "shutdown");
                m.putObject("params");
                send(m);
                if (!p.waitFor(5, TimeUnit.SECONDS)) destroy();
            } catch (WorkerException e) {
                destroy();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroy();
            }
        }
        failAll(new WorkerException(WorkerException.WORKER_UNAVAILABLE, "Worker stopped", false));
    }
}
