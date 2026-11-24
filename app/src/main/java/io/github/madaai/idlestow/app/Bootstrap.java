package io.github.madaai.idlestow.app;

import io.github.madaai.idlestow.app.util.UiPrefs;
import io.github.madaai.idlestow.core.db.Database;
import io.github.madaai.idlestow.core.engine.EngineConfig;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.ipc.OptimizationWorker;
import io.github.madaai.idlestow.core.ipc.PythonWorker;
import io.github.madaai.idlestow.core.ipc.UnavailableWorker;
import io.github.madaai.idlestow.core.ipc.WorkerClient;
import io.github.madaai.idlestow.core.ipc.WorkerLauncher;
import io.github.madaai.idlestow.core.jobs.FaultInjector;
import io.github.madaai.idlestow.platform.Platforms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/** Builds the engine and its collaborators. Everything here blocks and must run off the FX thread. */
public final class Bootstrap {
    private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class);

    private Bootstrap() { }

    public record WorkerSetup(OptimizationWorker worker, boolean available, String note) { }

    /** Locates the Python worker; falls back to an explicit "unavailable" worker with a clear reason. */
    public static WorkerSetup createWorker() {
        try {
            Optional<WorkerLauncher> launcher = WorkerLauncher.locate();
            if (launcher.isPresent()) {
                OptimizationWorker w = new PythonWorker(new WorkerClient(launcher.get()));
                return new WorkerSetup(w, true, (launcher.get().bundled() ? "Bundled" : "Development")
                        + " Python worker at " + launcher.get().workingDir());
            }
        } catch (RuntimeException e) {
            LOG.warn("Worker discovery failed", e);
        }
        String reason = "The IdleStow Python worker was not found. Files can be indexed and analyzed, but"
                + " optimization and verification are unavailable.";
        return new WorkerSetup(new UnavailableWorker(reason), false, reason);
    }

    /** Opens the database, starts the engine and returns the application context. */
    public static AppContext create(LaunchArgs args, Path dataDir)
            throws Database.DatabaseUnavailableException, SQLException {
        var platform = Platforms.detect();
        WorkerSetup ws = createWorker();
        IdleStowEngine engine;
        try {
            engine = new IdleStowEngine(new EngineConfig(dataDir, platform, ws.worker(), Clock.systemUTC(),
                    args.readOnly(), FaultInjector.NONE));
        } catch (Database.DatabaseUnavailableException | SQLException | RuntimeException e) {
            ws.worker().close();
            throw e;
        }
        engine.start();
        return new AppContext(engine, args, UiPrefs.forDataDir(dataDir), ws.available(), ws.note());
    }

    /**
     * Moves a damaged database (and its WAL/SHM side files) aside as {@code idlestow.db.damaged-<timestamp>}.
     * Nothing is ever deleted. Returns the new location of the main database file.
     */
    public static Path moveDatabaseAside(Path dataDir) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path main = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dataDir, "idlestow.db*")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.contains(".damaged-")) continue;
                Path target = dataDir.resolve(n.replaceFirst("^idlestow\\.db", "idlestow.db.damaged-" + stamp));
                Files.move(p, target);
                if (n.equals("idlestow.db")) main = target;
            }
        }
        if (main == null) throw new IOException("No database file found in " + dataDir);
        return main;
    }
}
