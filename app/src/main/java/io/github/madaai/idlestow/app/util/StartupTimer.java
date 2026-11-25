package io.github.madaai.idlestow.app.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/** Logs milestones of the application start as milliseconds since the operating system process was created. */
public final class StartupTimer {
    private static final Logger LOG = LoggerFactory.getLogger(StartupTimer.class);
    private static final long PROCESS_START_MS = ProcessHandle.current().info().startInstant()
            .map(Instant::toEpochMilli).orElse(System.currentTimeMillis());

    private StartupTimer() { }

    /** Milliseconds since the process started. */
    public static long sinceStartMs() {
        return System.currentTimeMillis() - PROCESS_START_MS;
    }

    /** Logs the milestone and returns the line so a caller may also print it. */
    public static String mark(String milestone) {
        String line = "Startup: " + milestone + " at " + sinceStartMs() + " ms after process start";
        LOG.info(line);
        return line;
    }
}
