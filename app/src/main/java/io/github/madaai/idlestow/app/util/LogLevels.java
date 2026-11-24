package io.github.madaai.idlestow.app.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;

/** Switches IdleStow's log verbosity at runtime (logback). */
public final class LogLevels {
    private LogLevels() { }

    public static void applyVerbose(boolean verbose) {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext lc) {
            lc.getLogger("io.github.madaai.idlestow").setLevel(verbose ? Level.DEBUG : Level.INFO);
        }
    }
}
