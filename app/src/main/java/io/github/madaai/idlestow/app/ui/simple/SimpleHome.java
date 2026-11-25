package io.github.madaai.idlestow.app.ui.simple;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.Actions;
import io.github.madaai.idlestow.app.ui.Branding;
import io.github.madaai.idlestow.app.ui.Dialogs;
import io.github.madaai.idlestow.app.ui.Icons;
import io.github.madaai.idlestow.app.ui.ViewId;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.Coalescer;
import io.github.madaai.idlestow.app.util.Fmt;
import io.github.madaai.idlestow.core.db.CandidateRepository;
import io.github.madaai.idlestow.core.db.CandidateRepository.Candidate;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.model.Enums.CandidateStatus;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Simple mode: one calm screen for people who just want space back. It shows one big status message with one
 * main action, a few plain numbers, and the folders IdleStow watches. It only ever offers transparent native
 * compression, where files stay in place and open normally. Archives and every other option are in the Advanced
 * view. Each action goes through the same engine calls (and safety checks) as the Advanced view.
 */
public final class SimpleHome {

    /** What the screen shows; loaded in the background. */
    record Data(List<MonitoredRoot> roots, long nativeCount, long nativeSavings, long otherPending, long reclaimed,
                long indexedFiles, long activeJobs, long attention, boolean scanning, long scannedFiles,
                boolean paused, long inactivityDays) { }

    private final AppContext ctx;
    private final Runnable switchToAdvanced;
    private final BorderPane root = new BorderPane();
    private final VBox body = new VBox(18);
    private final Button pauseButton = UI.button("Pause", Icons.PAUSE, "flat");
    private final Coalescer coalescer;
    private final Timeline poll;
    private boolean loading;
    private boolean visible;
    private Data shown;

    public SimpleHome(AppContext ctx, Runnable switchToAdvanced) {
        this.ctx = ctx;
        this.switchToAdvanced = switchToAdvanced;
        this.coalescer = new Coalescer(500, Platform::runLater, this::refresh);
        root.getStyleClass().add("simple-root");
        root.setTop(header());
        body.setPadding(new Insets(8, 28, 28, 28));
        body.setMaxWidth(760);
        body.setAlignment(Pos.TOP_CENTER);
        HBox center = new HBox(body);
        center.setAlignment(Pos.TOP_CENTER);
        HBox.setHgrow(body, Priority.ALWAYS);
        ScrollPane scroll = new ScrollPane(center);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("simple-scroll");
        root.setCenter(scroll);
        // While something is happening, refresh every 1.5 s so progress feels alive.
        poll = new Timeline(new KeyFrame(Duration.millis(1500), e -> refresh()));
        poll.setCycleCount(Timeline.INDEFINITE);
        ctx.engine().addListener(e -> {
            if (visible) coalescer.request();
        });
    }

    public Node node() {
        return root;
    }

    public void onShown() {
        visible = true;
        refresh();
    }

    public void onHidden() {
        visible = false;
        poll.stop();
    }

    // ------------------------------------------------------------------ header

    private Node header() {
        Node logo = Branding.logo(36);
        Label name = UI.label("IdleStow", "app-title");
        Label slogan = UI.label("Idle files. Reclaimed space.", "slogan");
        HBox brand = new HBox(10, logo, new VBox(1, name, slogan));
        brand.setAlignment(Pos.CENTER_LEFT);
        pauseButton.setOnAction(e -> togglePause());
        Button advanced = UI.button("Advanced view", Icons.SETTINGS, switchToAdvanced, "flat");
        advanced.setTooltip(UI.tip("Show every detail and option: file explorer, recommendations, jobs, history,"
                + " recovery and settings"));
        HBox bar = new HBox(10, brand, UI.spacer(), pauseButton, advanced);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(14, 22, 10, 22));
        return bar;
    }

    // ------------------------------------------------------------------ loading

    public CompletableFuture<Void> refresh() {
        if (loading) return CompletableFuture.completedFuture(null);
        loading = true;
        return Background.supply(this::load).handle((d, err) -> {
            loading = false;
            if (err != null) {
                shown = null;
                body.getChildren().setAll(UI.banner(Icons.WARNING, "Could not load: " + Background.message(err),
                        "error", UI.button("Try again", Icons.REFRESH, this::refresh)));
            } else {
                render(d);
            }
            return null;
        });
    }

    private Data load() throws Exception {
        IdleStowEngine e = ctx.engine();
        // One aggregate query instead of loading the candidate rows; they are loaded only when the user acts.
        CandidateRepository.MethodSummary nat = e.pendingSummary(Method.NATIVE);
        long otherPending = Math.max(0, e.pendingRecommendationCount() - nat.count());
        IdleStowEngine.ScanStatus scan = e.scanStatus();
        return new Data(e.roots(), nat.count(), nat.estSavings(), otherPending, e.queries().currentReclaimed(),
                e.queries().totals().files(), e.jobs().countActive(), e.recoveryRecords().countNeedingAttention(),
                scan.running(), scan.files(), e.paused(), e.settings().inactivityDays());
    }

    // ------------------------------------------------------------------ rendering

    private void render(Data d) {
        updatePauseButton(d.paused());
        boolean busy = d.scanning() || d.activeJobs() > 0;
        if (busy && visible) poll.play();
        else poll.stop();
        if (d.equals(shown) && !body.getChildren().isEmpty() ) return; // nothing changed
        shown = d;
        body.getChildren().setAll(hero(d), stats(d), folders(d), safetyNote(d));
    }

    private Node hero(Data d) {
        IdleStowEngine e = ctx.engine();
        Icons icon;
        String title;
        String text;
        Node action = null;
        Node extra = null;
        if (d.roots().isEmpty()) {
            icon = Icons.FOLDER_OPEN;
            title = "Let's find some space";
            text = "Pick a folder with files you rarely use, like Documents or Downloads. IdleStow looks for files"
                    + " you haven't opened in a while. It only reads the folders you choose.";
            action = big("Choose a folder", Icons.PLUS, this::addFolder);
        } else if (d.activeJobs() > 0) {
            icon = Icons.COMPRESS;
            title = "Freeing up space...";
            text = "IdleStow is shrinking files and checking each one. You can keep using your computer, and you"
                    + " can close this window.";
            extra = progress();
        } else if (d.scanning()) {
            icon = Icons.SEARCH;
            title = "Looking through your files...";
            text = String.format("%,d files checked so far. Nothing is changed while IdleStow looks.",
                    d.scannedFiles());
            extra = progress();
        } else if (d.nativeCount() > 0) {
            icon = Icons.BOLT;
            title = "You can free up about " + Fmt.size(d.nativeSavings());
            text = Fmt.plural(d.nativeCount(), "file") + (d.nativeCount() == 1
                    ? " hasn't" : " haven't") + " been used in a while. IdleStow can"
                    + " shrink them. They stay in the same place and open just like before.";
            Button go = big("Free up space", Icons.COMPRESS, this::freeUp);
            if (e.readOnly()) {
                go.setDisable(true);
                text += "\n\nIdleStow was started in read-only mode, so it won't change anything.";
            } else if (!ctx.workerAvailable()) {
                go.setDisable(true);
                text += "\n\nThe part of IdleStow that checks files isn't working right now (" + ctx.workerNote()
                        + "), so it won't change anything.";
            } else if (d.paused()) {
                text += "\n\nIdleStow is paused. Resume it to start.";
                go.setDisable(true);
            }
            action = go;
            Hyperlink list = new Hyperlink("See which files");
            list.setOnAction(ev -> ctx.nav().show(ViewId.RECOMMENDATIONS));
            extra = list;
        } else {
            icon = Icons.CHECK;
            title = "All tidy";
            text = "Nothing needs shrinking right now. IdleStow will suggest files once they've been idle for "
                    + Fmt.plural(d.inactivityDays(), "day") + ".";
            if (d.otherPending() > 0) {
                text += " There " + (d.otherPending() == 1 ? "is 1 other suggestion" : "are " + d.otherPending()
                        + " other suggestions") + " (archives) in the Advanced view.";
            }
            action = UI.button("Check again", Icons.REFRESH, () -> {
                e.scan(null);
                refresh();
            });
        }
        Node ic = icon.node(52, "icon-accent");
        Label t = UI.label(title, "simple-hero-title");
        t.setWrapText(true);
        t.setTextAlignment(TextAlignment.CENTER);
        Label tx = UI.wrapped(text, "simple-hero-text");
        tx.setTextAlignment(TextAlignment.CENTER);
        tx.setMaxWidth(560);
        VBox card = new VBox(12, ic, t, tx);
        card.setAlignment(Pos.CENTER);
        if (extra != null) card.getChildren().add(extra);
        if (action != null) card.getChildren().add(action);
        card.getStyleClass().addAll("card", "simple-hero");
        return card;
    }

    private static Button big(String text, Icons icon, Runnable action) {
        Button b = UI.button(text, icon, action, "primary", "simple-big");
        b.setDefaultButton(false);
        return b;
    }

    private static Node progress() {
        ProgressBar p = new ProgressBar(-1);
        p.setPrefWidth(320);
        return p;
    }

    private Node stats(Data d) {
        HBox row = new HBox(14,
                stat("Space saved", Fmt.size(d.reclaimed()), "measured on disk"),
                stat("Files checked", String.format("%,d", d.indexedFiles()), "in your folders"),
                stat("Folders", Long.toString(d.roots().size()), "watched by IdleStow"));
        row.setAlignment(Pos.CENTER);
        return row;
    }

    private static Node stat(String title, String value, String sub) {
        VBox v = new VBox(2, UI.label(title, "simple-stat-title"), UI.label(value, "simple-stat-value"),
                UI.label(sub, "simple-stat-sub"));
        v.setAlignment(Pos.CENTER);
        v.getStyleClass().addAll("card", "simple-stat");
        HBox.setHgrow(v, Priority.ALWAYS);
        v.setMaxWidth(Double.MAX_VALUE);
        return v;
    }

    private Node folders(Data d) {
        VBox list = new VBox(6);
        for (MonitoredRoot r : d.roots()) {
            Label n = UI.label(r.displayName(), "h3");
            Label p = UI.label(r.path().toString(), "muted", "small");
            Button remove = UI.button("Remove", null, () -> removeFolder(r), "flat");
            remove.setTooltip(UI.tip("Stop watching this folder. Your files are not touched."));
            remove.setAccessibleText("Stop watching " + r.displayName());
            HBox row = new HBox(10, Icons.EXPLORER.node(20, "icon-accent"), new VBox(1, n, p), UI.spacer(), remove);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("simple-folder");
            list.getChildren().add(row);
        }
        Button add = UI.button("Add a folder", Icons.PLUS, this::addFolder, "flat");
        HBox head = new HBox(UI.label("Your folders", "h2"), UI.spacer(), add);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(10, head);
        if (d.roots().isEmpty()) {
            card.getChildren().add(UI.muted("No folders yet."));
        } else {
            card.getChildren().add(list);
        }
        card.getStyleClass().add("card");
        return card;
    }

    private Node safetyNote(Data d) {
        Label text = UI.wrapped("Shrinking a file never deletes it. Every file is checked after it's shrunk, and you"
                + " can undo any change. Compression is not a backup, so keep your own backups of important files.", "muted");
        HBox.setHgrow(text, Priority.ALWAYS);
        Button undo = UI.button("Undo changes", Icons.RECOVERY, () -> ctx.nav().show(ViewId.RECOVERY), "flat");
        undo.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox row = new HBox(12, Icons.SHIELD.node(22, "icon-success"), text, undo);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("card-flat");
        VBox v = new VBox(10, row);
        if (d.attention() > 0) {
            v.getChildren().add(0, UI.banner(Icons.WARNING, Fmt.plural(d.attention(), "item") + " need your attention.",
                    "warn", UI.button("Review", null, () -> ctx.nav().show(ViewId.RECOVERY))));
        }
        return v;
    }

    // ------------------------------------------------------------------ actions

    private void addFolder() {
        Actions.addFolder(ctx, ctx.nav().window(), true, r -> refresh());
    }

    private void removeFolder(MonitoredRoot r) {
        if (!Dialogs.confirm(ctx.nav().window(), "Stop watching folder", "Stop watching " + r.displayName() + "?",
                "IdleStow forgets this folder. Your files stay exactly where they are, and anything IdleStow already"
                        + " shrank can still be undone.", "Stop watching")) return;
        Background.run(() -> ctx.engine().removeRoot(r.id())).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(ctx.nav().window(), "Cannot remove", Background.message(err));
            refresh();
        });
    }

    private void freeUp() {
        // The candidate rows are loaded only now, when the user asks to act on them.
        Background.supply(() -> ctx.engine().recommendations(List.of(CandidateStatus.PENDING), null, null,
                CandidateRepository.Sort.SAVINGS, 20_000).stream().filter(c -> c.method() == Method.NATIVE)
                .toList()).whenComplete((list, err) -> {
            if (err != null) {
                Dialogs.error(ctx.nav().window(), "Could not load the files", Background.message(err));
                return;
            }
            confirmAndApprove(list);
        });
    }

    private void confirmAndApprove(List<Candidate> list) {
        if (list.isEmpty()) {
            toast("Nothing to shrink right now.");
            refresh();
            return;
        }
        long savings = list.stream().mapToLong(Candidate::estSavings).sum();
        String text = "IdleStow will shrink " + Fmt.plural(list.size(), "file") + " you haven't used in a while,"
                + " saving about " + Fmt.size(savings) + " (an estimate).\n\n"
                + "- Files stay in the same place and open normally.\n"
                + "- Each file is checked again just before it changes, and verified afterwards.\n"
                + "- No file is deleted, and you can undo it any time.";
        if (!Dialogs.confirm(ctx.nav().window(), "Free up space", "Shrink " + Fmt.plural(list.size(), "file") + "?",
                text, "Free up space")) return;
        approve(list, jobs -> {
            if (jobs.isEmpty()) toast("Those files changed or are in use, so nothing was started.");
            else toast("Freeing up space. You can close this window; IdleStow finishes in the background.");
        });
    }

    private void approve(List<Candidate> list, Consumer<List<Long>> done) {
        Background.supply(() -> ctx.engine().approveRecommendations(list, false)).whenComplete((jobs, err) -> {
            if (err != null) Dialogs.error(ctx.nav().window(), "Could not start", Background.message(err));
            else done.accept(jobs);
            refresh();
        });
    }

    private void togglePause() {
        if (ctx.engine().paused()) {
            ctx.engine().resumeAll();
            toast("Resumed");
        } else {
            ctx.engine().pauseAll(null);
            toast("Paused. Anything running stops after the current file.");
        }
        updatePauseButton(ctx.engine().paused());
        refresh();
    }

    private void updatePauseButton(boolean paused) {
        pauseButton.setText(paused ? "Resume" : "Pause");
        pauseButton.setGraphic((paused ? Icons.PLAY : Icons.PAUSE).node(16));
        UI.tooltip(pauseButton, paused ? "Let IdleStow work again" : "Stop all IdleStow work until you resume");
    }

    private void toast(String s) {
        if (ctx.nav() != null) ctx.nav().toast(s);
    }
}
