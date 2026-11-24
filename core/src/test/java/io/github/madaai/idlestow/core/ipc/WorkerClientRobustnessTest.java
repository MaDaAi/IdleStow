package io.github.madaai.idlestow.core.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WorkerClient against tiny fake Python workers written into a temp directory. */
class WorkerClientRobustnessTest {
    @TempDir Path dir;

    private static boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private WorkerClient client(String script) throws Exception {
        Assumptions.assumeTrue(pythonAvailable(), "python not available");
        Path s = dir.resolve("fake_worker.py");
        Files.writeString(s, script);
        return new WorkerClient(new WorkerLauncher(List.of("python", "-u", s.toString()), dir, dir.toString(), false));
    }

    private static final String PRELUDE = """
            import sys, json
            def send(o):
                sys.stdout.write(json.dumps(o) + "\\n"); sys.stdout.flush()
            def ready(v=1):
                send({"v": v, "type": "ready", "worker": "fake", "version": "0", "protocol": v, "capabilities": {"x": True}})
            """;

    private static final String ECHO_LOOP = """
            for line in sys.stdin:
                try:
                    m = json.loads(line)
                except Exception:
                    continue
                if m.get("op") == "shutdown":
                    break
                if m.get("op") == "cancel":
                    continue
                send({"v": 1, "id": m["id"], "type": "result", "result": {"echo": m["op"]}})
            """;

    @Test
    void garbageAndOversizedLinesBeforeReadyAreIgnored() throws Exception {
        String script = PRELUDE + """
                sys.stdout.write("this is not json at all\\n")
                sys.stdout.write("{broken json\\n")
                sys.stdout.write("\\n")
                sys.stdout.write("x" * (1024 * 1024 + 500) + "\\n")
                sys.stdout.write('{"v":1,"type":"result"}\\n')
                sys.stdout.flush()
                ready()
                sys.stdout.write("garbage after ready\\n"); sys.stdout.flush()
                """ + ECHO_LOOP;
        try (WorkerClient c = client(script)) {
            JsonNode caps = c.ensureStarted();
            assertTrue(caps.path("x").asBoolean());
            JsonNode r = c.request("hello", null, null, Duration.ofSeconds(10));
            assertEquals("hello", r.path("echo").asText());
            assertEquals("again", c.request("again", null, null, Duration.ofSeconds(10)).path("echo").asText());
        }
    }

    @Test
    void oversizedLineBetweenResponsesDoesNotBreakTheStream() throws Exception {
        String script = PRELUDE + """
                ready()
                for line in sys.stdin:
                    m = json.loads(line)
                    if m.get("op") == "shutdown":
                        break
                    sys.stdout.write("y" * (2 * 1024 * 1024) + "\\n")
                    send({"v": 1, "id": m["id"], "type": "result", "result": {"echo": m["op"]}})
                """;
        try (WorkerClient c = client(script)) {
            assertEquals("one", c.request("one", null, null, Duration.ofSeconds(15)).path("echo").asText());
            assertEquals("two", c.request("two", null, null, Duration.ofSeconds(15)).path("echo").asText());
        }
    }

    @Test
    void wrongProtocolVersionMessagesAreIgnored() throws Exception {
        String script = PRELUDE + """
                ready(7)
                send({"v": 2, "type": "ready", "capabilities": {}})
                ready()
                for line in sys.stdin:
                    m = json.loads(line)
                    if m.get("op") == "shutdown":
                        break
                    send({"v": 99, "id": m["id"], "type": "result", "result": {"echo": "WRONG"}})
                    send({"v": 1, "id": m["id"], "type": "result", "result": {"echo": "right"}})
                """;
        try (WorkerClient c = client(script)) {
            c.ensureStarted();
            assertEquals("right", c.request("op", null, null, Duration.ofSeconds(10)).path("echo").asText());
        }
    }

    @Test
    void onlyWrongVersionReadyMeansHandshakeNeverCompletes() throws Exception {
        String script = PRELUDE + """
                ready(7)
                import time
                time.sleep(60)
                """;
        try (WorkerClient c = client(script)) {
            // The client waits up to 20 s for the handshake; a wrong-version "ready" must not satisfy it.
            WorkerException e = assertThrows(WorkerException.class, c::ensureStarted);
            assertEquals(WorkerException.TIMEOUT, e.code());
        }
    }

    @Test
    void workerThatExitsImmediatelyFailsAndRestartsAreBounded() throws Exception {
        try (WorkerClient c = client("import sys\nsys.exit(3)\n")) {
            int unavailableWithoutRestart = 0;
            int attempts = 8;
            for (int i = 0; i < attempts; i++) {
                WorkerException e = assertThrows(WorkerException.class, c::ensureStarted, "attempt " + i);
                assertTrue(e.code().equals(WorkerException.WORKER_UNAVAILABLE) || e.code().equals(WorkerException.TIMEOUT), e.code());
                if (e.getMessage().contains("not restarted")) unavailableWithoutRestart++;
            }
            // 1 initial start + at most 3 restarts in the window; later attempts are refused outright
            assertTrue(unavailableWithoutRestart >= attempts - 4,
                    "restarts must be bounded (<=3 per 5 min) but only " + unavailableWithoutRestart + " of " + attempts + " were refused");
        }
    }

    @Test
    void unansweredRequestTimesOut() throws Exception {
        String script = PRELUDE + """
                ready()
                for line in sys.stdin:
                    m = json.loads(line)
                    if m.get("op") == "shutdown":
                        break
                """;
        try (WorkerClient c = client(script)) {
            WorkerClient.Call call = c.submit("never", null, null);
            long t0 = System.nanoTime();
            WorkerException e = assertThrows(WorkerException.class, () -> call.await(Duration.ofSeconds(2)));
            assertEquals(WorkerException.TIMEOUT, e.code());
            assertTrue(Duration.ofNanos(System.nanoTime() - t0).getSeconds() < 15, "timeout must not hang");
        }
    }

    @Test
    void workerErrorResponsesBecomeStructuredExceptions() throws Exception {
        String script = PRELUDE + """
                ready()
                for line in sys.stdin:
                    m = json.loads(line)
                    if m.get("op") == "shutdown":
                        break
                    send({"v": 1, "id": m["id"], "type": "error", "error": {"code": "NOT_FOUND", "message": "nope", "retryable": False}})
                """;
        try (WorkerClient c = client(script)) {
            WorkerException e = assertThrows(WorkerException.class, () -> c.request("x", null, null, Duration.ofSeconds(10)));
            assertEquals("NOT_FOUND", e.code());
            assertEquals(false, e.retryable());
        }
    }

    @Test
    void workerCrashDuringRequestFailsThePendingCall() throws Exception {
        String script = PRELUDE + """
                ready()
                sys.stdin.readline()
                sys.exit(1)
                """;
        try (WorkerClient c = client(script)) {
            WorkerException e = assertThrows(WorkerException.class, () -> c.request("x", null, null, Duration.ofSeconds(10)));
            assertEquals(WorkerException.WORKER_CRASHED, e.code());
        }
    }

    @Test
    void closedClientRefusesNewWork() throws Exception {
        WorkerClient c = client(PRELUDE + "ready()\n" + ECHO_LOOP);
        c.ensureStarted();
        c.close();
        WorkerException e = assertThrows(WorkerException.class, c::ensureStarted);
        assertEquals(WorkerException.WORKER_UNAVAILABLE, e.code());
    }

    @Test
    void missingInterpreterIsReportedAsUnavailable() throws Exception {
        try (WorkerClient c = new WorkerClient(new WorkerLauncher(List.of("definitely-not-a-real-python-binary"), dir, dir.toString(), false))) {
            WorkerException e = assertThrows(WorkerException.class, c::ensureStarted);
            assertEquals(WorkerException.WORKER_UNAVAILABLE, e.code());
        }
    }
}
