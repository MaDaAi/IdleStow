package io.github.madaai.idlestow.app;

import io.github.madaai.idlestow.core.engine.AppDirectories;
import io.github.madaai.idlestow.core.engine.InstanceLock;
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Process entry point. It deliberately does not extend {@link Application} so that the application also
 * starts from a plain classpath (JavaFX is then loaded as ordinary libraries).
 *
 * <p>Order matters: the data directory and the {@code idlestow.logdir} property are fixed before any SLF4J
 * logger exists, so the rolling log file lands in the right place.
 */
public final class Launcher {

    /** Handed to {@link IdleStowApp}, which JavaFX instantiates reflectively. */
    static volatile LaunchArgs launchArgs;
    static volatile Path dataDir;
    static volatile InstanceLock instanceLock;
    static volatile int exitCode;
    /**
     * The engine, started on a background thread while the JavaFX toolkit initialises. Only set when the Terms of
     * Use are already accepted (nothing may open the database before that) or in the smoke test.
     */
    static volatile java.util.concurrent.CompletableFuture<AppContext> enginePreload;

    private Launcher() { }

    public static void main(String[] argv) {
        LaunchArgs args;
        try {
            args = LaunchArgs.parse(argv);
        } catch (LaunchArgs.ArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(LaunchArgs.USAGE);
            System.exit(2);
            return;
        }
        if (args.help()) {
            System.out.println(LaunchArgs.USAGE);
            System.exit(0);
            return;
        }
        if (args.selfTestDir() != null) {
            System.exit(selfTest(args.selfTestDir()));
            return;
        }
        int code = run(args);
        System.exit(code);
    }

    /** Headless end-to-end check for packaging: no JavaFX, no instance lock, never touches the user's data. */
    static int selfTest(Path dir) {
        var setup = Bootstrap.createWorker();
        try {
            var platform = io.github.madaai.idlestow.platform.Platforms.detect();
            var result = io.github.madaai.idlestow.core.engine.SelfTest.run(dir, platform, setup.worker());
            for (String line : result.lines()) System.out.println(line);
            return result.passed() ? 0 : 1;
        } catch (Throwable t) {
            System.out.println("SELF-TEST FAILED: " + t);
            return 1;
        } finally {
            setup.worker().close();
        }
    }

    static int run(LaunchArgs args) {
        Path data;
        try {
            if (args.dataDir() != null) {
                data = args.dataDir();
            } else if (args.smokeTest()) {
                data = Files.createTempDirectory("idlestow-smoke-").toAbsolutePath();
            } else {
                data = AppDirectories.dataDir();
            }
            Files.createDirectories(data);
            System.setProperty("idlestow.home", data.toString());
            System.setProperty("idlestow.logdir", AppDirectories.logDir(data).toString());
            Files.createDirectories(AppDirectories.logDir(data));
        } catch (IOException | RuntimeException e) {
            System.err.println("Cannot prepare the data folder: " + e.getMessage());
            return 1;
        }
        // Only now may a logger be created.
        Logger log = LoggerFactory.getLogger(Launcher.class);
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> log.error("Uncaught exception on {}", t.getName(), ex));
        log.info("Starting IdleStow (data dir {}, background={}, readOnly={}, smoke={})", data, args.background(),
                args.readOnly(), args.smokeTest());

        try {
            var lock = InstanceLock.tryAcquire(data);
            if (lock.isEmpty()) {
                log.info("Another IdleStow instance owns {} - asking it to show its window", data);
                try {
                    Files.writeString(InstanceLock.activationSignal(data), Instant.now().toString());
                } catch (IOException e) {
                    log.warn("Could not signal the running instance: {}", e.toString());
                }
                System.out.println("IdleStow is already running; asked it to come to the front.");
                return args.smokeTest() ? 1 : 0;
            }
            instanceLock = lock.get();
        } catch (IOException e) {
            log.error("Cannot acquire the instance lock", e);
            System.err.println("Cannot acquire the instance lock: " + e.getMessage());
            return 1;
        }

        launchArgs = args;
        dataDir = data;
        exitCode = 0;
        io.github.madaai.idlestow.app.util.StartupTimer.mark("launcher ready");
        if (args.smokeTest() || io.github.madaai.idlestow.app.ui.Legal.accepted(
                io.github.madaai.idlestow.app.util.UiPrefs.forDataDir(data))) {
            var future = new java.util.concurrent.CompletableFuture<AppContext>();
            enginePreload = future;
            Thread t = new Thread(() -> {
                try {
                    future.complete(Bootstrap.create(args, data));
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            }, "idlestow-engine-start");
            t.setDaemon(true);
            t.start();
        }
        try {
            Application.launch(IdleStowApp.class);
        } catch (Throwable t) { // includes UnsupportedOperationException (no display) and linkage errors
            log.error("The user interface could not start", t);
            System.err.println("The user interface could not start: " + t);
            exitCode = 1;
        }
        releaseLock();
        return exitCode;
    }

    static void releaseLock() {
        InstanceLock l = instanceLock;
        instanceLock = null;
        if (l != null) {
            try {
                l.close();
            } catch (IOException ignored) {
                // process is ending
            }
        }
    }
}
