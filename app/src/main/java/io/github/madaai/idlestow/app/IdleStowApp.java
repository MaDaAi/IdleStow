package io.github.madaai.idlestow.app;

import io.github.madaai.idlestow.app.tray.TrayService;
import io.github.madaai.idlestow.app.ui.Actions;
import io.github.madaai.idlestow.app.ui.Dialogs;
import io.github.madaai.idlestow.app.ui.Legal;
import io.github.madaai.idlestow.app.ui.MainWindow;
import io.github.madaai.idlestow.app.ui.Theme;
import io.github.madaai.idlestow.app.ui.ViewId;
import io.github.madaai.idlestow.app.ui.onboarding.OnboardingWizard;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.LogLevels;
import io.github.madaai.idlestow.app.util.StartupTimer;
import io.github.madaai.idlestow.app.util.UiPrefs;
import io.github.madaai.idlestow.core.db.Database;
import io.github.madaai.idlestow.core.engine.InstanceLock;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** JavaFX application: bootstrap, main window, tray and orderly shutdown. Instantiated by {@link Launcher}. */
public final class IdleStowApp extends Application implements AppContext.Lifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(IdleStowApp.class);

    private Stage primary;
    private LaunchArgs args;
    private Path dataDir;
    private AppContext ctx;
    private MainWindow window;
    private TrayService tray;
    private ScheduledExecutorService activationWatcher;
    private volatile boolean exiting;

    @Override
    public void start(Stage stage) {
        this.primary = stage;
        this.args = Launcher.launchArgs;
        this.dataDir = Launcher.dataDir;
        Platform.setImplicitExit(false);
        StartupTimer.mark("JavaFX started");
        try {
            if (args.smokeTest()) {
                startSmoke();
            } else {
                bootstrap();
            }
        } catch (RuntimeException e) {
            LOG.error("Startup failed", e);
            Launcher.exitCode = 1;
            Platform.exit();
        }
    }

    // ------------------------------------------------------------------ normal start

    private void bootstrap() {
        // The Terms of Use are accepted before IdleStow opens its database or touches anything.
        if (!Legal.requestAcceptance(null, UiPrefs.forDataDir(dataDir))) {
            LOG.info("Terms of Use not accepted; exiting without changes");
            exit();
            return;
        }
        engine().whenComplete((c, err) -> {
            if (err != null) {
                onBootstrapFailure(err);
                return;
            }
            ctx = c;
            afterEngine();
        });
    }

    /** The engine started during toolkit initialisation when possible; delivered on the FX thread. */
    private java.util.concurrent.CompletableFuture<AppContext> engine() {
        var pre = Launcher.enginePreload;
        Launcher.enginePreload = null;
        if (pre == null) return Background.supply(() -> Bootstrap.create(args, dataDir));
        var out = new java.util.concurrent.CompletableFuture<AppContext>();
        pre.whenComplete((c, e) -> Background.fx(() -> {
            if (e != null) out.completeExceptionally(e);
            else out.complete(c);
        }));
        return out;
    }

    private void onBootstrapFailure(Throwable err) {
        Throwable root = err;
        while (root.getCause() != null && !(root instanceof Database.DatabaseUnavailableException)) {
            root = root.getCause();
        }
        if (!(root instanceof Database.DatabaseUnavailableException)) {
            LOG.error("Cannot start the engine", err);
            Dialogs.error(null, "IdleStow cannot start", Background.message(err));
            exit();
            return;
        }
        LOG.error("Database unavailable", err);
        ButtonType open = new ButtonType("Open data folder", ButtonBar.ButtonData.LEFT);
        ButtonType fresh = new ButtonType("Move the damaged database aside and start fresh",
                ButtonBar.ButtonData.OK_DONE);
        ButtonType quit = new ButtonType("Exit", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("IdleStow database problem");
        a.setHeaderText("IdleStow cannot open its database");
        a.setContentText(Background.message(root) + "\n\nYour files are not affected. The database only holds IdleStow's"
                + " index, history and settings. You can open the data folder to inspect or back it up, or move the"
                + " damaged database aside (it is renamed, never deleted) and start with an empty one. Recovery"
                + " records inside the old database are kept in the renamed file.");
        a.getButtonTypes().setAll(open, fresh, quit);
        Theme.style(a);
        Optional<ButtonType> r = a.showAndWait();
        if (r.isPresent() && r.get() == open) {
            Actions.openFolder(null, dataDir);
            onBootstrapFailure(err);
        } else if (r.isPresent() && r.get() == fresh) {
            if (!Dialogs.confirm(null, "Start fresh", "Move the database aside?",
                    "The damaged database is renamed to idlestow.db.damaged-<time> in " + dataDir + " and IdleStow"
                            + " starts with an empty database. Nothing is deleted.", "Move aside")) {
                onBootstrapFailure(err);
                return;
            }
            Background.supply(() -> {
                Path moved = Bootstrap.moveDatabaseAside(dataDir);
                LOG.warn("Moved damaged database to {}", moved);
                return Bootstrap.create(args, dataDir);
            }).whenComplete((c, e2) -> {
                if (e2 != null) {
                    Dialogs.error(null, "Cannot start fresh", Background.message(e2));
                    exit();
                } else {
                    ctx = c;
                    afterEngine();
                }
            });
        } else {
            exit();
        }
    }

    private void afterEngine() {
        ctx.setLifecycle(this);
        LogLevels.applyVerbose(ctx.engine().settings().verboseLogging());
        Theme.set(Theme.Mode.of(ctx.engine().settings().theme()));
        window = new MainWindow(ctx, primary);
        window.installCloseHandler();
        boolean trayOk = false;
        if (TrayService.supported()) {
            tray = new TrayService(ctx, this::showWindow, this::exit);
            trayOk = tray.install();
            if (!trayOk) tray = null;
        }
        startActivationWatcher();
        window.start();
        boolean onboarding = !ctx.engine().settings().onboardingCompleted();
        boolean simpleFirst = !"advanced".equals(ctx.prefs().getString("uiMode", "simple"));
        if (onboarding && simpleFirst) {
            // New users start in the Simple view, which walks them through adding a folder. The full setup
            // wizard is for the Advanced view (Settings keeps every option it sets, with safe defaults).
            Background.run(() -> ctx.engine().settingsStore().update("onboarding.completed", "true"))
                    .exceptionally(t -> null);
            showWindow();
        } else if (onboarding) {
            OnboardingWizard w = OnboardingWizard.create(ctx, this::showWindow);
            w.show();
        } else if (args.background() && trayOk) {
            LOG.info("Started in the background (tray only)");
        } else {
            if (args.background()) LOG.info("No system tray available: showing the window instead");
            showWindow();
        }
    }

    private void startActivationWatcher() {
        Path signal = InstanceLock.activationSignal(dataDir);
        try {
            Files.deleteIfExists(signal); // stale signal from an earlier run
        } catch (java.io.IOException ignored) {
            // harmless
        }
        activationWatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idlestow-activation");
            t.setDaemon(true);
            return t;
        });
        activationWatcher.scheduleWithFixedDelay(() -> {
            try {
                if (Files.exists(signal)) {
                    Files.deleteIfExists(signal);
                    Platform.runLater(this::showWindow);
                }
            } catch (java.io.IOException | RuntimeException e) {
                LOG.debug("Activation check failed: {}", e.toString());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void showWindow() {
        Platform.runLater(() -> {
            if (window == null) return;
            if (window.currentView() == null && !window.simpleMode()) {
                window.restoreGeometry();
                window.showInitial();
            }
            if (!primary.isShowing()) {
                primary.show();
                StartupTimer.mark("first window shown");
            }
            primary.setIconified(false);
            primary.toFront();
            primary.requestFocus();
        });
    }

    @Override
    public boolean trayAvailable() {
        return tray != null;
    }

    @Override
    public void notifyUser(String title, String message, boolean error) {
        if (tray != null) tray.notify(title, message, error);
    }

    /** Graceful exit: stop jobs at safe points, shut the worker down, release the instance lock. */
    @Override
    public void exit() {
        if (exiting) return;
        exiting = true;
        Runnable fx = () -> {
            if (window != null) {
                window.saveGeometry();
                window.stop();
            }
            if (tray != null) tray.remove();
            if (activationWatcher != null) activationWatcher.shutdownNow();
            Thread t = new Thread(() -> {
                try {
                    if (ctx != null) ctx.engine().close();
                } catch (RuntimeException e) {
                    LOG.warn("Engine shutdown failed: {}", e.toString());
                }
                Launcher.releaseLock();
                Platform.exit();
                System.exit(Launcher.exitCode);
            }, "idlestow-shutdown");
            t.setDaemon(false);
            t.start();
        };
        if (Platform.isFxApplicationThread()) fx.run();
        else Platform.runLater(fx);
    }

    // ------------------------------------------------------------------ smoke test

    /**
     * Builds the complete main window and every view against a temporary data directory (and a throw-away sample
     * folder), loads each view once, then prints SMOKE OK and exits 0, or exits 1 on any failure.
     */
    private void startSmoke() {
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idlestow-smoke-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.schedule(() -> {
            System.out.println("SMOKE FAILED: timeout");
            Launcher.releaseLock();
            Runtime.getRuntime().halt(1);
        }, 150, TimeUnit.SECONDS);
        engine().thenCompose(c -> Background.supply(() -> {
            seedSample(c);
            return c;
        })).whenComplete((c, err) -> {
            if (err != null) {
                smokeFail(err);
                return;
            }
            ctx = c;
            try {
                smokeBuild();
            } catch (RuntimeException | Error e) {
                smokeFail(e);
            }
        });
    }

    private void seedSample(AppContext c) {
        try {
            String custom = System.getProperty("idlestow.smoke.sampleParent");
            // Default: next to the data directory (the OS temp dir is under AppData, which IdleStow protects).
            Path parent = custom != null ? Files.createDirectories(Path.of(custom))
                    : c.engine().dataDir().toAbsolutePath().getParent();
            Path sample = Files.createTempDirectory(parent, "idlestow-smoke-files-");
            Path sub = Files.createDirectories(sample.resolve("docs"));
            StringBuilder sb = new StringBuilder();
            while (sb.length() < 200_000) sb.append("IdleStow smoke test line\n");
            Files.writeString(sub.resolve("notes.txt"), sb);
            Files.writeString(sample.resolve("readme.md"), "hello");
            c.engine().addRoot(sample);
            c.engine().scan(null).get(60, TimeUnit.SECONDS);
            System.out.println("Smoke: indexed sample folder " + sample);
        } catch (java.io.IOException e) {
            if (String.valueOf(e.getMessage()).contains("cannot be monitored")) {
                // the chosen location is protected by design; indexing is then simply not exercised
                System.out.println("Smoke: sample indexing SKIPPED (" + e.getMessage() + ")");
            } else {
                smokeProblem = "sample folder could not be indexed: " + e;
                System.out.println("Smoke: " + smokeProblem);
            }
        } catch (Exception e) {
            smokeProblem = "sample folder could not be indexed: " + e;
            System.out.println("Smoke: " + smokeProblem);
        }
    }

    /** Set when the smoke test detects a real problem; makes the smoke test fail. */
    private volatile String smokeProblem;

    private void smokeBuild() {
        ctx.setLifecycle(this);
        window = new MainWindow(ctx, primary);
        // Construct the wizard content too (not shown).
        OnboardingWizard wizard = OnboardingWizard.create(ctx, null);
        wizard.content();
        window.scene().getRoot().applyCss();
        window.loadAllViews().thenCompose(v -> window.loadSimpleView()).whenComplete((v, err) -> {
            if (err != null) {
                smokeFail(err);
                return;
            }
            window.scene().getRoot().applyCss();
            window.scene().getRoot().layout();
            Background.supply(() -> {
                String formats;
                try {
                    formats = ctx.engine().worker().supportedFormats().toString();
                } catch (Exception e) {
                    formats = "unavailable (" + Background.message(e) + ")";
                    // A packaged build must find its bundled worker.
                    if (System.getProperty("jpackage.app-path") != null) {
                        smokeProblem = "the bundled Python worker is unavailable";
                    }
                }
                return "Worker: " + ctx.engine().worker().status() + " formats=" + formats + " ("
                        + ctx.workerNote() + ")";
            }).whenComplete((line, e2) -> {
                System.out.println(e2 != null ? "Worker: unknown (" + Background.message(e2) + ")" : line);
                if (smokeProblem != null) {
                    System.out.println("SMOKE FAILED: " + smokeProblem);
                    smokeFinish(1);
                } else {
                    System.out.println(StartupTimer.mark("smoke complete"));
                    System.out.println("SMOKE OK");
                    smokeFinish(0);
                }
            });
        });
    }

    private void smokeFail(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        System.out.println("SMOKE FAILED: " + Background.message(t));
        System.out.println(sw);
        LOG.error("Smoke test failed", t);
        smokeFinish(1);
    }

    private void smokeFinish(int code) {
        Launcher.exitCode = code;
        Thread t = new Thread(() -> {
            try {
                if (ctx != null) ctx.engine().close();
            } catch (RuntimeException ignored) {
                // exiting anyway
            }
            Platform.exit();
        }, "idlestow-smoke-exit");
        t.start();
    }

    @SuppressWarnings("unused")
    private void unusedViewId() {
        ViewId.values();
    }
}
