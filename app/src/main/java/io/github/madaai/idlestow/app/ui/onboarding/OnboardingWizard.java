package io.github.madaai.idlestow.app.ui.onboarding;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.Actions;
import io.github.madaai.idlestow.app.ui.Branding;
import io.github.madaai.idlestow.app.ui.Dialogs;
import io.github.madaai.idlestow.app.ui.Icons;
import io.github.madaai.idlestow.app.ui.Theme;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.ui.views.SettingsView;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.Fmt;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.model.Enums.AuthorizationMode;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.platform.VolumeInfo;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * First-run wizard. Nothing is preselected, scanning is read-only, and autonomy is never activated here:
 * the intention is recorded and the exact policy is confirmed after the first scan.
 */
public final class OnboardingWizard {
    private static final int LAST = 7;

    private final AppContext ctx;
    private final Runnable onFinished;
    private final Stage stage = new Stage();
    private final StackPane stepHost = new StackPane();
    private final HBox dots = new HBox(8);
    private final Button back = UI.button("Back", null);
    private final Button next = UI.button("Next", null, "primary");
    private final Button skip = UI.button("Skip for now", null, "flat");
    private final Label stepTitle = UI.label("", "h1");
    private final VBox root = new VBox(14);
    private final Timeline scanPoll = new Timeline();
    private int step;

    // step state
    private final ListView<String> folderList = new ListView<>();
    private List<MonitoredRoot> roots = new ArrayList<>();
    private final ComboBox<Integer> threshold = new ComboBox<>(FXCollections.observableArrayList(7, 14, 30, 60, 90,
            180, 365));
    private final ComboBox<Method> method = new ComboBox<>(FXCollections.observableArrayList(Method.NATIVE,
            Method.ZIP_COPY));
    private final ToggleGroup modeGroup = new ToggleGroup();
    private RadioButton manualRb;
    private RadioButton askRb;
    private RadioButton autoRb;
    private final Map<Long, CheckBox> autonomyFolders = new LinkedHashMap<>();
    private final CheckBox bgScan = new CheckBox("Keep scanning for idle files in the background");
    private final CheckBox login = new CheckBox("Start IdleStow when I sign in");
    private final ProgressBar scanBar = new ProgressBar(-1);
    private final Label scanText = UI.label("", "detail-value");
    private final Button cancelScan = UI.button("Cancel scan", Icons.STOP, "danger");
    private boolean scanStarted;
    private final VBox resultBox = new VBox(10);

    private OnboardingWizard(AppContext ctx, Runnable onFinished) {
        this.ctx = ctx;
        this.onFinished = onFinished;
        build();
    }

    public static OnboardingWizard create(AppContext ctx, Runnable onFinished) {
        return new OnboardingWizard(ctx, onFinished);
    }

    public Stage stage() {
        return stage;
    }

    /** The wizard content (used by the smoke test, which builds it without showing it). */
    public Node content() {
        return root;
    }

    public void show() {
        showStep(0);
        stage.show();
    }

    // ------------------------------------------------------------------ frame

    private void build() {
        stage.setTitle("Welcome to IdleStow");
        stage.getIcons().addAll(Branding.windowIcons());
        back.setOnAction(e -> go(step - 1));
        next.setOnAction(e -> advance());
        skip.setOnAction(e -> {
            scanPoll.stop();
            stage.close();
            if (onFinished != null) onFinished.run();
        });
        skip.setTooltip(UI.tip("Close the setup. It will be offered again next time IdleStow starts."));
        dots.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i <= LAST; i++) {
            Region d = new Region();
            d.setMinSize(10, 10);
            d.setMaxSize(10, 10);
            d.getStyleClass().add("wizard-step-dot");
            dots.getChildren().add(d);
        }
        HBox head = new HBox(12, Branding.logo(36), UI.label("IdleStow setup", "h2"), UI.spacer(), dots);
        head.setAlignment(Pos.CENTER_LEFT);
        HBox foot = new HBox(10, skip, UI.spacer(), back, next);
        foot.setAlignment(Pos.CENTER_RIGHT);
        stepHost.setMinHeight(380);
        VBox.setVgrow(stepHost, Priority.ALWAYS);
        root.getChildren().addAll(head, UI.divider(), stepTitle, stepHost, foot);
        root.setPadding(new Insets(22));
        Scene scene = new Scene(root, 820, 640);
        Theme.register(scene);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            scanPoll.stop();
            if (onFinished != null) onFinished.run();
        });
        scanPoll.getKeyFrames().add(new KeyFrame(Duration.millis(400), e -> pollScan()));
        scanPoll.setCycleCount(Animation.INDEFINITE);

        threshold.setValue(30);
        method.setValue(Method.NATIVE);
        threshold.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Integer d) {
                return d == null ? "" : d + " days";
            }

            @Override
            public Integer fromString(String s) {
                return null;
            }
        });
        method.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Method m) {
                return m == null ? "" : m.label();
            }

            @Override
            public Method fromString(String s) {
                return null;
            }
        });
    }

    private void go(int target) {
        if (target < 0 || target > LAST) return;
        scanPoll.stop();
        showStep(target);
    }

    private void showStep(int s) {
        step = s;
        for (int i = 0; i < dots.getChildren().size(); i++) {
            Node d = dots.getChildren().get(i);
            d.getStyleClass().removeAll("wizard-step-dot", "wizard-step-dot-active", "wizard-step-dot-done");
            d.getStyleClass().add(i == s ? "wizard-step-dot-active" : i < s ? "wizard-step-dot-done"
                    : "wizard-step-dot");
        }
        back.setDisable(s == 0 || s == 6 || s == 7);
        next.setDisable(false);
        next.setText(s == LAST ? "Go to Overview" : s == 6 ? "Next" : "Next");
        Node content;
        switch (s) {
            case 0 -> {
                stepTitle.setText("Welcome");
                content = welcome();
            }
            case 1 -> {
                stepTitle.setText("Choose folders");
                content = folders();
            }
            case 2 -> {
                stepTitle.setText("When is a file idle?");
                content = inactivity();
            }
            case 3 -> {
                stepTitle.setText("How should files be compressed?");
                content = compression();
            }
            case 4 -> {
                stepTitle.setText("Who decides?");
                content = operatingMode();
            }
            case 5 -> {
                stepTitle.setText("Background");
                content = background();
            }
            case 6 -> {
                stepTitle.setText("First scan");
                content = initialScan();
            }
            default -> {
                stepTitle.setText("Results");
                content = results();
            }
        }
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        stepHost.getChildren().setAll(sp);
    }

    private void advance() {
        switch (step) {
            case 1 -> {
                if (roots.isEmpty()) {
                    Dialogs.info(stage, "Choose a folder", "Add at least one folder",
                            "IdleStow only looks at folders you authorize. Add one to continue.");
                    return;
                }
            }
            case 2 -> save("policy.inactivityDays", String.valueOf(threshold.getValue()));
            case 3 -> save("opt.preferredMethod", method.getValue().name());
            case 4 -> {
                AuthorizationMode m = askRb.isSelected() ? AuthorizationMode.ASK
                        : manualRb.isSelected() ? AuthorizationMode.MANUAL : AuthorizationMode.ASK;
                // Autonomous is never activated here; remember the intention and confirm after the scan.
                ctx.prefs().put("autonomyIntent", autoRb.isSelected());
                ctx.prefs().save();
                Background.run(() -> ctx.engine().setMode(m)).exceptionally(t -> null);
            }
            case 5 -> {
                save("bg.enabled", String.valueOf(bgScan.isSelected()));
                if (login.isSelected()) {
                    Background.run(() -> {
                        ctx.engine().platform().startupRegistration().register(Actions.startupCommand(ctx));
                        ctx.engine().settingsStore().update("bg.startAtLogin", "true");
                    }).whenComplete((v, err) -> {
                        if (err != null) Dialogs.error(stage, "Start at login", Background.message(err));
                    });
                }
            }
            case 6 -> {
                if (ctx.engine().scanStatus().running()) return;
            }
            case 7 -> {
                finish();
                return;
            }
            default -> { }
        }
        go(step + 1);
    }

    private void save(String key, String value) {
        Background.run(() -> ctx.engine().settingsStore().update(key, value)).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(stage, "Cannot save", Background.message(err));
        });
    }

    // ------------------------------------------------------------------ steps

    private Node welcome() {
        VBox v = new VBox(12);
        v.getChildren().addAll(
                UI.wrapped("IdleStow finds files you have not used for a while and shrinks them with lossless,"
                        + " verified compression to reclaim disk space. Idle files. Reclaimed space.", "detail-value"),
                point(Icons.SHIELD, "It never deletes your files",
                        "Files are only compressed, and every result is verified. You can restore anything at any time."),
                point(Icons.EYE, "It only looks where you allow",
                        "You choose the folders. IdleStow never scans the rest of your computer and sends nothing"
                                + " anywhere: no accounts, no telemetry."),
                point(Icons.WARNING, "Compression is not a backup",
                        "A compressed file is still one copy. Keep your own backups of anything important."),
                point(Icons.CHECK, "You stay in control",
                        "By default IdleStow asks before it optimizes anything, and shows exactly what will change."));
        return v;
    }

    private static Node point(Icons icon, String title, String text) {
        VBox t = new VBox(2, UI.label(title, "h3"), UI.wrapped(text, "muted"));
        HBox h = new HBox(12, icon.node(24, "icon-accent"), t);
        h.setAlignment(Pos.TOP_LEFT);
        return h;
    }

    private Node folders() {
        refreshRoots();
        folderList.setPrefHeight(150);
        folderList.setPlaceholder(UI.muted("No folders yet. Nothing is preselected."));
        Button add = UI.button("Add folder...", Icons.PLUS, () -> Actions.addFolder(ctx, stage, false,
                r -> refreshRoots()), "primary");
        Button remove = UI.button("Remove from list", Icons.X, () -> {
            int i = folderList.getSelectionModel().getSelectedIndex();
            if (i < 0 || i >= roots.size()) return;
            MonitoredRoot r = roots.get(i);
            Background.run(() -> ctx.engine().removeRoot(r.id())).whenComplete((x, err) -> refreshRoots());
        });
        return new VBox(12,
                UI.wrapped("Add the folders IdleStow may index. Typical choices are Documents, Downloads, Projects or"
                        + " a data drive.", "detail-value"),
                folderList, UI.hbox(8, add, remove),
                UI.banner(Icons.LOCK, "Authorization boundary: IdleStow reads and (with your approval) compresses files"
                        + " only inside these folders. You can revoke or remove a folder at any time in Settings.",
                        "info"),
                UI.banner(Icons.SHIELD, "Built-in protections: Windows and system folders, program files, other"
                        + " programs' data, IdleStow's own data, cloud-synchronized folders and anything you pin or"
                        + " exclude are never changed, and overlapping folders are refused.", "info"));
    }

    private void refreshRoots() {
        Background.supply(() -> ctx.engine().roots()).whenComplete((list, err) -> {
            if (err != null) return;
            roots = new ArrayList<>(list);
            folderList.setItems(FXCollections.observableArrayList(list.stream().map(r -> r.path().toString()).toList()));
        });
    }

    private Node inactivity() {
        return new VBox(12,
                UI.wrapped("A file counts as idle when there is no evidence of use for this long.", "detail-value"),
                UI.hbox(10, UI.label("Idle threshold", "detail-key"), threshold),
                UI.wrapped("IdleStow combines several signals (modification time, last access when the filesystem"
                        + " tracks it reliably, what it observes itself, and your own declarations). Each"
                        + " classification has a confidence level. When evidence is weak or missing the file is treated"
                        + " as 'unknown' and left alone.", "muted"),
                UI.wrapped("Observation period: after a folder is added IdleStow first watches it for a while (14 days"
                        + " by default; adjustable in Settings) before trusting inactivity for files that have no"
                        + " reliable access times.", "muted"));
    }

    private Node compression() {
        VBox v = new VBox(12,
                UI.wrapped("Two ways to save space:", "detail-value"),
                point(Icons.COMPRESS, "Transparent native compression",
                        "The filesystem compresses the file in place (NTFS). The path and every program keep working,"
                                + " and it is fully reversible. This is the recommended default."),
                point(Icons.FILE, "Archives (ZIP, tar.zst)",
                        "A compressed copy next to the file, or - only with your explicit confirmation - a replacement"
                                + " of the file by a verified archive. Archives change how files can be opened."),
                UI.hbox(10, UI.label("Preferred method", "detail-key"), method));
        v.getChildren().add(UI.label("What your folders support", "section-title"));
        Label support = UI.muted("Checking...");
        v.getChildren().add(support);
        List<Path> paths = roots.stream().map(MonitoredRoot::path).toList();
        Background.supply(() -> {
            StringBuilder sb = new StringBuilder();
            for (Path p : paths) {
                try {
                    VolumeInfo vi = ctx.engine().inspectVolume(p);
                    sb.append(p).append(": ").append(vi.fileSystemType()).append(" - native compression ")
                            .append(vi.nativeCompression() ? "supported" : "not supported"
                                    + (vi.nativeCompressionNote() == null || vi.nativeCompressionNote().isBlank() ? ""
                                    : " (" + vi.nativeCompressionNote() + ")")).append('\n');
                } catch (java.io.IOException e) {
                    sb.append(p).append(": cannot inspect (").append(e.getMessage()).append(")\n");
                }
            }
            return sb.length() == 0 ? "No folders chosen." : sb.toString().trim();
        }).whenComplete((text, err) -> support.setText(err != null ? Background.message(err) : text));
        if (!ctx.workerAvailable()) {
            v.getChildren().add(UI.banner(Icons.WARNING, "The Python worker was not found, so archive methods and"
                    + " verification are unavailable. " + ctx.workerNote(), "warn"));
        }
        return v;
    }

    private Node operatingMode() {
        manualRb = new RadioButton("Manual - IdleStow only recommends; I start everything myself.");
        askRb = new RadioButton("Ask - I approve recommendations, then IdleStow runs them. (Recommended)");
        autoRb = new RadioButton("Autonomous - IdleStow optimizes eligible files by itself within a policy I confirm.");
        for (RadioButton rb : List.of(manualRb, askRb, autoRb)) {
            rb.setToggleGroup(modeGroup);
            rb.setWrapText(true);
        }
        askRb.setSelected(true);
        VBox folders = new VBox(6);
        folders.setPadding(new Insets(0, 0, 0, 26));
        autonomyFolders.clear();
        for (MonitoredRoot r : roots) {
            CheckBox cb = new CheckBox("Allow autonomy in " + r.path());
            autonomyFolders.put(r.id(), cb);
            folders.getChildren().add(cb);
        }
        folders.managedProperty().bind(autoRb.selectedProperty());
        folders.visibleProperty().bind(autoRb.selectedProperty());
        return new VBox(12, manualRb, askRb, autoRb, folders,
                UI.wrapped("Autonomous mode is not switched on by this wizard. If you choose it, IdleStow shows you the"
                        + " exact policy after the first scan and only activates it once you confirm. It only uses"
                        + " reversible native compression.", "muted"));
    }

    private Node background() {
        bgScan.setWrapText(true);
        login.setDisable(!ctx.engine().platform().startupRegistration().supported());
        return new VBox(12,
                UI.wrapped("Both options are off by default. You can change them any time in Settings > Automation.",
                        "detail-value"),
                bgScan, login,
                UI.wrapped("In the background IdleStow keeps indexing with low priority, pauses on battery and when you"
                        + " are busy, and lives in the system tray.", "muted"));
    }

    private Node initialScan() {
        scanBar.setMaxWidth(Double.MAX_VALUE);
        cancelScan.setOnAction(e -> ctx.engine().cancelScan());
        next.setDisable(true);
        VBox v = new VBox(12, UI.wrapped("IdleStow now reads file names, sizes and timestamps (never contents) in your"
                + " folders. This scan is read-only: nothing is changed.", "detail-value"), scanBar, scanText,
                cancelScan);
        if (!scanStarted) {
            scanStarted = true;
            ctx.engine().scan(null);
        }
        scanPoll.play();
        pollScan();
        return v;
    }

    private void pollScan() {
        IdleStowEngine.ScanStatus st = ctx.engine().scanStatus();
        boolean running = st.running();
        scanBar.setProgress(running ? -1 : 1);
        scanText.setText(running ? st.message() + " - " + String.format("%,d", st.files()) + " files, "
                + Fmt.size(st.bytes()) : "Finished: " + st.message());
        cancelScan.setDisable(!running);
        if (step == 6) next.setDisable(running);
    }

    private Node results() {
        resultBox.getChildren().setAll(UI.muted("Collecting results..."));
        Background.supply(() -> {
            var e = ctx.engine();
            return new long[]{e.queries().totals().files(), e.queries().totals().logical(),
                e.pendingRecommendationCount(), e.pendingSavings()};
        }).whenComplete((r, err) -> {
            resultBox.getChildren().clear();
            if (err != null) {
                resultBox.getChildren().add(UI.banner(Icons.WARNING, Background.message(err), "error"));
                return;
            }
            resultBox.getChildren().addAll(
                    UI.label(String.format("%,d files indexed (%s)", r[0], Fmt.size(r[1])), "h2"),
                    UI.label(Fmt.plural(r[2], "recommendation") + (r[3] > 0 ? ", potential savings " + Fmt.estimate(r[3])
                            + " (estimate)" : ""), "h2"),
                    UI.wrapped(r[2] == 0 ? "No idle files were found yet. IdleStow needs to observe your folders for a"
                            + " while; recommendations appear as files become idle." : "Review them in Recommendations."
                            + " Nothing will change until you approve it.", "muted"));
            if (Boolean.TRUE.equals(ctx.prefs().getBool("autonomyIntent", false))) {
                resultBox.getChildren().add(UI.banner(Icons.INFO, "You chose autonomous mode. When you press Go to"
                        + " Overview IdleStow shows the exact policy for your confirmation.", "info"));
            }
        });
        return resultBox;
    }

    // ------------------------------------------------------------------ finish

    private void finish() {
        next.setDisable(true);
        Map<Long, Boolean> autonomy = new LinkedHashMap<>();
        autonomyFolders.forEach((id, cb) -> autonomy.put(id, cb.isSelected()));
        boolean intent = ctx.prefs().getBool("autonomyIntent", false);
        Background.run(() -> {
            for (Map.Entry<Long, Boolean> e : autonomy.entrySet()) {
                if (intent && e.getValue()) ctx.engine().setRootAutonomy(e.getKey(), true);
            }
            ctx.engine().settingsStore().update("onboarding.completed", "true");
        }).whenComplete((v, err) -> {
            if (err != null) {
                Dialogs.error(stage, "Cannot finish the setup", Background.message(err));
                next.setDisable(false);
                return;
            }
            scanPoll.stop();
            stage.close();
            if (onFinished != null) onFinished.run();
            if (intent) offerAutonomy();
        });
    }

    private void offerAutonomy() {
        Background.supply(() -> ctx.engine().currentAutonomyPolicy()).whenComplete((p, err) -> {
            if (err != null) return;
            javafx.stage.Window owner = ctx.nav() == null ? null : ctx.nav().window();
            if (SettingsView.confirmAutonomy(owner, p)) {
                Background.run(() -> ctx.engine().confirmAutonomy(p)).whenComplete((v, e) -> {
                    if (e != null) Dialogs.error(owner, "Autonomy was not enabled", Background.message(e));
                    else if (ctx.nav() != null) ctx.nav().toast("Autonomous mode enabled");
                });
            }
        });
    }
}
