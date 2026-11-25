package io.github.madaai.idlestow.app.ui;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.simple.SimpleHome;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.ui.views.BaseView;
import io.github.madaai.idlestow.app.ui.views.ExplorerView;
import io.github.madaai.idlestow.app.ui.views.HistoryView;
import io.github.madaai.idlestow.app.ui.views.JobsView;
import io.github.madaai.idlestow.app.ui.views.OverviewView;
import io.github.madaai.idlestow.app.ui.views.RecommendationsView;
import io.github.madaai.idlestow.app.ui.views.RecoveryView;
import io.github.madaai.idlestow.app.ui.views.SettingsView;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.Coalescer;
import io.github.madaai.idlestow.core.db.JobRepository;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.model.Enums.CloseBehavior;
import io.github.madaai.idlestow.core.model.Enums.JobState;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The main application window. Two presentations share it: the Simple view (one screen, see {@link SimpleHome}) and
 * the Advanced view (sidebar, toolbar, the active view and the status bar). The choice is remembered.
 */
public final class MainWindow implements Navigator {
    private static final KeyCombination[] NUMBER_KEYS = {
        new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.CONTROL_DOWN),
        new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.CONTROL_DOWN),
        new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.CONTROL_DOWN),
        new KeyCodeCombination(KeyCode.DIGIT4, KeyCombination.CONTROL_DOWN),
        new KeyCodeCombination(KeyCode.DIGIT5, KeyCombination.CONTROL_DOWN),
        new KeyCodeCombination(KeyCode.DIGIT6, KeyCombination.CONTROL_DOWN),
        new KeyCodeCombination(KeyCode.DIGIT7, KeyCombination.CONTROL_DOWN)};

    private final AppContext ctx;
    private final Stage stage;
    private final Scene scene;
    private final Map<ViewId, Function<AppContext, BaseView>> factories = new EnumMap<>(ViewId.class);
    private final Map<ViewId, BaseView> views = new EnumMap<>(ViewId.class);
    private final Map<ViewId, ToggleButton> navButtons = new EnumMap<>(ViewId.class);
    private final ToggleGroup navGroup = new ToggleGroup();
    private final StackPane viewHost = new StackPane();
    private final Label titleLabel = UI.label("", "h1");
    private final HBox toolbarHost = new HBox(8);
    private StatusBar statusBar; // built with the Advanced chrome, which is created on first use
    private final Label toast = new Label();
    private final PauseTransition toastTimer = new PauseTransition(Duration.seconds(4.5));
    private final Button pauseButton = UI.button("Pause", Icons.PAUSE, "flat");
    private Coalescer statusCoalescer;
    private final Coalescer jobWatchCoalescer;
    private final Map<Long, JobState> knownJobs = new HashMap<>();
    private boolean jobsSeeded;
    private ViewId current;
    private final StackPane host = new StackPane();
    private BorderPane advancedRoot;
    private SimpleHome simple;
    private boolean simpleMode;
    private volatile boolean windowShowing;
    private volatile boolean statusLive;
    private boolean started;

    public MainWindow(AppContext ctx, Stage stage) {
        this.ctx = ctx;
        this.stage = stage;
        ctx.setNavigator(this);
        factories.put(ViewId.OVERVIEW, OverviewView::new);
        factories.put(ViewId.EXPLORER, ExplorerView::new);
        factories.put(ViewId.RECOMMENDATIONS, RecommendationsView::new);
        factories.put(ViewId.JOBS, JobsView::new);
        factories.put(ViewId.HISTORY, HistoryView::new);
        factories.put(ViewId.RECOVERY, RecoveryView::new);
        factories.put(ViewId.SETTINGS, SettingsView::new);

        jobWatchCoalescer = new Coalescer(700, Platform::runLater, this::watchJobs);
        toast.getStyleClass().add("toast");
        toast.setVisible(false);
        toast.setMouseTransparent(true);
        toast.setWrapText(true);
        toast.setMaxWidth(640);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 18, 0));
        toastTimer.setOnFinished(e -> toast.setVisible(false));
        VBox.setVgrow(host, Priority.ALWAYS);
        StackPane top = new StackPane(new VBox(updateBanner(), host), toast);

        scene = new Scene(top, 1280, 800);
        Theme.register(scene);
        installShortcuts();
        stage.setScene(scene);
        stage.setTitle("IdleStow");
        stage.setMinWidth(980);
        stage.setMinHeight(620);
        stage.getIcons().addAll(Branding.windowIcons());
        stage.showingProperty().addListener((o, was, now) -> {
            windowShowing = now;
            updateStatusLive();
        });
        ctx.engine().addListener(this::onEngineEvent);
        applyThemeFromSettings();
    }

    public Scene scene() {
        return scene;
    }

    // ------------------------------------------------------------------ construction

    /** Builds the Advanced chrome (sidebar, toolbar, status bar) the first time it is needed. */
    private void ensureAdvanced() {
        if (advancedRoot != null) return;
        statusBar = new StatusBar(ctx);
        statusCoalescer = new Coalescer(400, Platform::runLater, statusBar::refresh);
        BorderPane root = new BorderPane();
        root.setLeft(buildSidebar());
        VBox centerBox = new VBox(buildToolbar(), buildBanners(), viewHost);
        VBox.setVgrow(viewHost, Priority.ALWAYS);
        root.setCenter(centerBox);
        root.setBottom(statusBar);
        advancedRoot = root;
        updatePauseButton();
        if (started) statusBar.start();
        updateStatusLive();
    }

    /** The status bar only polls while the window is showing the Advanced view. */
    private void updateStatusLive() {
        boolean live = windowShowing && !simpleMode && statusBar != null;
        statusLive = live;
        if (statusBar != null) statusBar.setActive(live);
    }

    private Node buildSidebar() {
        Node logo = Branding.logo(34);
        Label name = UI.label("IdleStow", "app-title");
        Label slogan = UI.label("Idle files. Reclaimed space.", "slogan");
        slogan.setWrapText(true);
        HBox head = new HBox(10, logo, new VBox(1, name, slogan));
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(4, 6, 14, 6));

        VBox box = new VBox(4);
        box.getChildren().add(head);
        for (ViewId id : ViewId.values()) {
            ToggleButton b = new ToggleButton(id.title());
            b.setGraphic(id.icon().node(20));
            b.setToggleGroup(navGroup);
            b.getStyleClass().add("nav-button");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setTooltip(UI.tip(id.title() + "  (Ctrl+" + (id.ordinal() + 1) + ")"));
            b.setOnAction(e -> show(id));
            b.setAccessibleText(id.title());
            navButtons.put(id, b);
            box.getChildren().add(b);
        }
        // a selected toggle must not be de-selectable
        navGroup.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null && current != null) navButtons.get(current).setSelected(true);
        });
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        Button simpleBtn = UI.button("Simple view", Icons.TARGET, this::showSimple, "flat");
        simpleBtn.setMaxWidth(Double.MAX_VALUE);
        simpleBtn.setAlignment(Pos.CENTER_LEFT);
        simpleBtn.setTooltip(UI.tip("Switch to the simple one-screen view"));
        Label ver = UI.label("v" + AppContext.version() + "  -  local only, no telemetry", "slogan");
        ver.setWrapText(true);
        box.getChildren().addAll(grow, simpleBtn, ver);
        box.setPadding(new Insets(16, 12, 14, 12));
        box.setPrefWidth(224);
        box.setMinWidth(224);
        box.getStyleClass().add("sidebar");
        return box;
    }

    private Node buildToolbar() {
        Button scan = UI.button("Scan now", Icons.REFRESH, this::scanNow, "flat");
        scan.setTooltip(UI.tip("Re-index all monitored folders (read-only)  F5"));
        pauseButton.setOnAction(e -> togglePause());
        toolbarHost.setAlignment(Pos.CENTER_LEFT);
        HBox bar = new HBox(14, titleLabel, toolbarHost, UI.spacer(), scan, pauseButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar-bar");
        return bar;
    }

    private Node buildBanners() {
        VBox v = new VBox(8);
        v.setPadding(new Insets(0, 18, 0, 18));
        if (!ctx.workerAvailable()) {
            v.getChildren().add(UI.banner(Icons.WARNING, "Optimization worker unavailable. " + ctx.workerNote()
                    + " Indexing and recommendations work; compression and verification are disabled.", "warn"));
        }
        if (ctx.engine().readOnly()) {
            v.getChildren().add(UI.banner(Icons.LOCK, "Read-only mode: IdleStow indexes and recommends but never"
                    + " changes a file.", "info"));
        }
        if (v.getChildren().isEmpty()) {
            v.setManaged(false);
            v.setVisible(false);
        } else {
            v.setPadding(new Insets(10, 18, 0, 18));
        }
        return v;
    }

    private void installShortcuts() {
        for (ViewId id : ViewId.values()) {
            scene.getAccelerators().put(NUMBER_KEYS[id.ordinal()], () -> show(id));
        }
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN), () -> {
            BaseView v = views.get(current);
            if (v != null && !v.focusSearch()) toast("This view has no search box");
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), this::scanNow);
    }

    // ------------------------------------------------------------------ navigation

    public BaseView view(ViewId id) {
        synchronized (views) {
            return views.computeIfAbsent(id, k -> {
                BaseView v = factories.get(k).apply(ctx);
                v.node().setVisible(false);
                v.node().setManaged(false);
                viewHost.getChildren().add(v.node());
                return v;
            });
        }
    }

    // ------------------------------------------------------------------ update banner

    /** A slim banner above both views while a newer release is known; hidden otherwise. */
    private Node updateBanner() {
        HBox holder = new HBox();
        holder.setManaged(false);
        holder.setVisible(false);
        var updates = ctx.updates();
        if (updates == null) return holder;
        updates.availableProperty().addListener((o, was, r) -> {
            if (r == null) {
                holder.getChildren().clear();
                holder.setManaged(false);
                holder.setVisible(false);
                return;
            }
            Button see = UI.button("See what's new", Icons.DOWNLOAD, () ->
                    io.github.madaai.idlestow.app.update.UpdateDialogs.offer(stage, ctx, updates, r), "primary");
            Button later = UI.button("Later", null, () -> {
                holder.setManaged(false);
                holder.setVisible(false);
            }, "flat");
            HBox b = UI.banner(Icons.DOWNLOAD, "IdleStow " + r.version() + " is available. You have "
                    + AppContext.version() + ".", "info", see, later);
            HBox.setHgrow(b, Priority.ALWAYS);
            holder.getChildren().setAll(b);
            holder.setPadding(new Insets(10, 18, 0, 18));
            holder.setManaged(true);
            holder.setVisible(true);
        });
        return holder;
    }

    // ------------------------------------------------------------------ simple / advanced

    public boolean simpleMode() {
        return simpleMode;
    }

    /** Shows the view the user last used: the Simple view (default for new users) or an Advanced view. */
    public void showInitial() {
        if ("advanced".equals(ctx.prefs().getString("uiMode", "simple"))) {
            show(lastView());
        } else {
            showSimple();
        }
    }

    public void showSimple() {
        if (simple == null) simple = new SimpleHome(ctx, () -> show(current == null ? ViewId.OVERVIEW : current));
        if (current != null) {
            BaseView old = views.get(current);
            if (old != null) old.onHidden();
        }
        simpleMode = true;
        updateStatusLive();
        host.getChildren().setAll(simple.node());
        ctx.prefs().put("uiMode", "simple");
        simple.onShown();
    }

    private void leaveSimple(ViewId target) {
        if (!simpleMode) return;
        simpleMode = false;
        if (simple != null) simple.onHidden();
        ensureAdvanced();
        host.getChildren().setAll(advancedRoot);
        updateStatusLive();
        ctx.prefs().put("uiMode", "advanced");
        if (current != null && current == target) {
            view(current).onShown(); // show(target) returns early for the current view, so resume it here
        }
    }

    /** Smoke test helper: builds and loads the simple view once. */
    public CompletableFuture<Void> loadSimpleView() {
        showSimple();
        return simple.refresh();
    }

    @Override
    public void show(ViewId id) {
        if (simpleMode) {
            leaveSimple(id);
        } else if (advancedRoot == null) {
            ensureAdvanced();
            host.getChildren().setAll(advancedRoot);
        }
        if (id == current) {
            return;
        }
        if (current != null) {
            BaseView old = views.get(current);
            if (old != null) {
                old.onHidden();
                old.node().setVisible(false);
                old.node().setManaged(false);
            }
        }
        current = id;
        BaseView v = view(id);
        v.node().setVisible(true);
        v.node().setManaged(true);
        navButtons.get(id).setSelected(true);
        titleLabel.setText(id.title());
        Node tb = v.toolbarContent();
        toolbarHost.getChildren().setAll(tb == null ? List.of() : List.of(tb));
        ctx.prefs().put("view", id.name());
        v.onShown();
    }

    public ViewId currentView() {
        return current;
    }

    @Override
    public void showHistory(String pathFilter) {
        ((HistoryView) view(ViewId.HISTORY)).filterByPath(pathFilter);
        show(ViewId.HISTORY);
    }

    @Override
    public void showRecovery(Long recoveryId) {
        ((RecoveryView) view(ViewId.RECOVERY)).select(recoveryId);
        show(ViewId.RECOVERY);
    }

    @Override
    public void showExplorer(Path path) {
        ((ExplorerView) view(ViewId.EXPLORER)).navigateTo(path);
        show(ViewId.EXPLORER);
    }

    @Override
    public void openPlanPreview(List<Long> itemIds) {
        PlanPreviewDialog.show(ctx, stage, itemIds, null);
    }

    @Override
    public void toast(String message) {
        toast.setText(message);
        toast.setVisible(true);
        toastTimer.playFromStart();
    }

    @Override
    public Window window() {
        return stage;
    }

    /** Builds and loads every view once; used by the smoke test. Completes exceptionally on any failure. */
    public CompletableFuture<Void> loadAllViews() {
        List<CompletableFuture<Void>> all = new ArrayList<>();
        for (ViewId id : ViewId.values()) {
            show(id);
            all.add(view(id).refresh());
        }
        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]));
    }

    // ------------------------------------------------------------------ actions

    private void scanNow() {
        Background.supply(() -> ctx.engine().roots().size()).whenComplete((n, err) -> {
            if (err != null) {
                toast("Cannot scan: " + Background.message(err));
            } else if (n == 0) {
                toast("No folders yet. Add a folder in Explorer or Settings first.");
            } else {
                ctx.engine().scan(null);
                toast("Scanning " + Math.toIntExact(n) + " folder(s) (read-only)...");
            }
        });
    }

    private void togglePause() {
        if (ctx.engine().paused()) {
            ctx.engine().resumeAll();
            toast("Resumed");
        } else {
            ctx.engine().pauseAll(null);
            toast("Paused until you resume. Running work stops after the current file.");
        }
        updatePauseButton();
    }

    private void updatePauseButton() {
        boolean paused = ctx.engine().paused();
        pauseButton.setText(paused ? "Resume" : "Pause");
        pauseButton.setGraphic((paused ? Icons.PLAY : Icons.PAUSE).node(16));
        UI.tooltip(pauseButton, paused ? "Resume optimization and background work"
                : "Pause optimization and background work until you resume");
    }

    // ------------------------------------------------------------------ engine events (any thread)

    private void onEngineEvent(IdleStowEngine.Event e) {
        List<BaseView> snapshot;
        synchronized (views) {
            snapshot = new ArrayList<>(views.values());
        }
        for (BaseView v : snapshot) v.onEngineEvent(e);
        if (statusLive) statusCoalescer.request();
        if (e == IdleStowEngine.Event.JOBS) jobWatchCoalescer.request();
        if (e == IdleStowEngine.Event.SETTINGS || e == IdleStowEngine.Event.STATUS) {
            Platform.runLater(() -> {
                applyThemeFromSettings();
                updatePauseButton();
            });
        }
    }

    private void applyThemeFromSettings() {
        Theme.Mode m = Theme.Mode.of(ctx.engine().settings().theme());
        if (m != Theme.mode()) Theme.set(m);
    }

    /** Detects jobs that just reached a terminal state and tells the user (toast + optional notification). */
    private void watchJobs() {
        Background.supply(() -> ctx.engine().jobs().jobs(30)).whenComplete((jobs, err) -> {
            if (err != null) return;
            for (JobRepository.Job j : jobs) {
                JobState before = knownJobs.put(j.id(), j.state());
                if (!jobsSeeded) continue;
                boolean terminalNow = j.state().terminal() || j.state() == JobState.RECOVERY_REQUIRED;
                if (terminalNow && (before == null || before != j.state())) {
                    String text = "Job #" + j.id() + " " + kindText(j.kind().name()) + ": " + j.state().name().toLowerCase()
                            + (j.message() == null ? "" : " - " + j.message());
                    toast(text);
                    boolean bad = j.state() == JobState.FAILED || j.state() == JobState.RECOVERY_REQUIRED;
                    if (ctx.engine().settings().notifications() && ctx.lifecycle() != null) {
                        ctx.lifecycle().notifyUser("IdleStow", text, bad);
                    }
                }
            }
            jobsSeeded = true;
        });
    }

    private static String kindText(String s) {
        return io.github.madaai.idlestow.app.util.Fmt.title(s).toLowerCase();
    }

    // ------------------------------------------------------------------ window state

    public void start() {
        windowShowing = stage.isShowing();
        started = true;
        updateStatusLive();
        if (statusBar != null) statusBar.start();
        watchJobs();
    }

    public void stop() {
        started = false;
        if (statusBar != null) statusBar.stop();
    }

    public void restoreGeometry() {
        double w = ctx.prefs().getDouble("win.w", 1280);
        double h = ctx.prefs().getDouble("win.h", 800);
        if (ctx.prefs().has("win.x") && ctx.prefs().has("win.y")) {
            double x = ctx.prefs().getDouble("win.x", 0);
            double y = ctx.prefs().getDouble("win.y", 0);
            if (!Screen.getScreensForRectangle(new Rectangle2D(x, y, Math.min(w, 200), Math.min(h, 200))).isEmpty()) {
                stage.setX(x);
                stage.setY(y);
            } else {
                stage.centerOnScreen();
            }
        } else {
            stage.centerOnScreen();
        }
        stage.setWidth(Math.max(980, w));
        stage.setHeight(Math.max(620, h));
        stage.setMaximized(ctx.prefs().getBool("win.max", false));
    }

    public void saveGeometry() {
        var p = ctx.prefs();
        p.put("win.max", stage.isMaximized());
        if (!stage.isMaximized() && !stage.isIconified() && stage.getWidth() > 100) {
            p.put("win.x", stage.getX());
            p.put("win.y", stage.getY());
            p.put("win.w", stage.getWidth());
            p.put("win.h", stage.getHeight());
        }
        if (current != null) p.put("view", current.name());
        p.put("uiMode", simpleMode ? "simple" : "advanced");
        p.save();
    }

    public ViewId lastView() {
        return ViewId.parse(ctx.prefs().getString("view", ViewId.OVERVIEW.name()), ViewId.OVERVIEW);
    }

    /** Close button: hide to tray or exit, per the user's setting. */
    public void requestClose() {
        saveGeometry();
        boolean tray = ctx.engine().settings().closeBehavior() == CloseBehavior.TRAY && ctx.lifecycle() != null
                && ctx.lifecycle().trayAvailable();
        if (tray) {
            stage.hide();
            if (!ctx.prefs().getBool("trayNoticeShown", false)) {
                ctx.prefs().put("trayNoticeShown", true);
                ctx.prefs().save();
                ctx.lifecycle().notifyUser("IdleStow is still running",
                        "IdleStow keeps running in the notification area. Use its tray icon to open or exit it.", false);
            }
            return;
        }
        Background.supply(() -> ctx.engine().jobs().countActive()).whenComplete((n, err) -> {
            if (err == null && n != null && n > 0) {
                boolean ok = Dialogs.confirm(stage, "Exit IdleStow", jobsAre(n) + " running",
                        "Jobs stop at the next safe point and anything interrupted is checked and recovered the next"
                                + " time IdleStow starts. Exit now?", "Exit");
                if (!ok) return;
            }
            if (ctx.lifecycle() != null) ctx.lifecycle().exit();
        });
    }

    private static String jobsAre(long n) {
        return io.github.madaai.idlestow.app.util.Fmt.plural(n, "job") + (n == 1 ? " is" : " are");
    }

    public void installCloseHandler() {
        stage.setOnCloseRequest(e -> {
            e.consume();
            requestClose();
        });
    }

    public Stage stage() {
        return stage;
    }
}
