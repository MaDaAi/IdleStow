package io.github.madaai.idlestow.app.ui.views;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.Dialogs;
import io.github.madaai.idlestow.app.ui.Icons;
import io.github.madaai.idlestow.app.ui.PlanPreviewDialog;
import io.github.madaai.idlestow.app.ui.Theme;
import io.github.madaai.idlestow.app.ui.ViewId;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.Fmt;
import io.github.madaai.idlestow.core.db.JobRepository;
import io.github.madaai.idlestow.core.db.JobRepository.Job;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.jobs.JobManager;
import io.github.madaai.idlestow.core.model.Enums.JobKind;
import io.github.madaai.idlestow.core.model.Enums.JobState;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Job list with live progress, pause/resume/cancel, inspection of operations and retry. */
public final class JobsView extends BaseView {
    private static final Set<JobState> ACTIVE = EnumSet.of(JobState.CREATED, JobState.AWAITING_APPROVAL,
            JobState.APPROVED, JobState.QUEUED, JobState.PREFLIGHT, JobState.RUNNING, JobState.VERIFYING,
            JobState.COMMITTING, JobState.PAUSED);

    private record StateFilter(String label, Set<JobState> states) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final ObservableList<Job> rows = FXCollections.observableArrayList();
    private final TableView<Job> table = new TableView<>(rows);
    private final ComboBox<StateFilter> filter = new ComboBox<>(FXCollections.observableArrayList(
            new StateFilter("All jobs", null),
            new StateFilter("Active", ACTIVE),
            new StateFilter("Queued", EnumSet.of(JobState.QUEUED, JobState.CREATED, JobState.APPROVED)),
            new StateFilter("Completed", EnumSet.of(JobState.COMPLETED, JobState.RESTORED)),
            new StateFilter("Failed", EnumSet.of(JobState.FAILED)),
            new StateFilter("Cancelled", EnumSet.of(JobState.CANCELLED)),
            new StateFilter("Recovery required", EnumSet.of(JobState.RECOVERY_REQUIRED))));
    private final Button pauseBtn = UI.button("Pause", Icons.PAUSE);
    private final Button resumeBtn = UI.button("Resume", Icons.PLAY);
    private final Button cancelBtn = UI.button("Cancel", Icons.STOP, "danger");
    private final Button inspectBtn = UI.button("Inspect", Icons.EYE);
    private final Button retryBtn = UI.button("Retry...", Icons.REFRESH);
    private final Button recoveryBtn = UI.button("Open Recovery", Icons.RECOVERY);
    private final VBox detail = new VBox(6);
    private final Timeline poll;
    private List<Job> all = List.of();

    public JobsView(AppContext ctx) {
        super(ctx);
        filter.setValue(filter.getItems().get(0));
        filter.valueProperty().addListener((o, a, b) -> applyFilter());
        configureTable();
        pauseBtn.setOnAction(e -> control(true));
        resumeBtn.setOnAction(e -> control(false));
        cancelBtn.setOnAction(e -> cancelSelected());
        inspectBtn.setOnAction(e -> inspect(selectedJob()));
        retryBtn.setOnAction(e -> retry(selectedJob()));
        recoveryBtn.setOnAction(e -> ctx.nav().show(ViewId.RECOVERY));

        FlowPane bar = new FlowPane(8, 6);
        bar.getChildren().addAll(UI.label("Show", "detail-key"), filter, pauseBtn, resumeBtn, cancelBtn, inspectBtn,
                retryBtn, recoveryBtn);
        detail.setPadding(new Insets(12));
        SplitPane split = new SplitPane(table, detail);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.7);
        VBox.setVgrow(split, Priority.ALWAYS);
        VBox root = new VBox(10, bar, split);
        root.setPadding(new Insets(14));
        setContent(root);

        poll = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        poll.setCycleCount(Animation.INDEFINITE);
        updateButtons();
        showDetail();
    }

    @Override
    public String title() {
        return "Jobs";
    }

    @Override
    protected Set<IdleStowEngine.Event> events() {
        return Set.of(IdleStowEngine.Event.JOBS, IdleStowEngine.Event.RECOVERY);
    }

    @Override
    protected long refreshIntervalMs() {
        return 400;
    }

    @Override
    public void onShown() {
        super.onShown();
        poll.play();
    }

    @Override
    public void onHidden() {
        super.onHidden();
        poll.stop();
    }

    /** While jobs are active, reload from the database and repaint live (in-memory) progress every second. */
    private void tick() {
        boolean anyActive = all.stream().anyMatch(j -> ACTIVE.contains(j.state()));
        if (anyActive) refresh();
        table.refresh();
        showDetail();
    }

    // ------------------------------------------------------------------ table

    private void configureTable() {
        table.setPlaceholder(UI.emptyState(Icons.JOBS, "No jobs yet",
                "Jobs appear here when you optimize, restore or verify files. Start one from Explorer or"
                        + " Recommendations."));
        table.getColumns().add(UI.numCol("#", 56, j -> Long.toString(j.id())));
        table.getColumns().add(UI.col("Kind", 120, j -> Fmt.title(j.kind().name())));
        table.getColumns().add(UI.col("Method", 190, j -> j.method() == null ? "" : j.method().label()));
        TableColumn<Job, Job> state = new TableColumn<>("State");
        state.setPrefWidth(150);
        state.setCellValueFactory(cd -> new javafx.beans.property.SimpleObjectProperty<>(cd.getValue()));
        state.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Job j, boolean empty) {
                super.updateItem(j, empty);
                if (empty || j == null) {
                    setGraphic(null);
                    return;
                }
                setGraphic(UI.badge(Fmt.title(j.state().name()), kindOf(j.state())));
            }
        });
        TableColumn<Job, Job> progress = new TableColumn<>("Progress");
        progress.setPrefWidth(190);
        progress.setCellValueFactory(cd -> new javafx.beans.property.SimpleObjectProperty<>(cd.getValue()));
        progress.setCellFactory(c -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar(0);
            private final Label text = UI.label("", "small", "muted");
            private final VBox box = new VBox(3, bar, text);

            {
                bar.setMaxWidth(Double.MAX_VALUE);
                box.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(Job j, boolean empty) {
                super.updateItem(j, empty);
                if (empty || j == null) {
                    setGraphic(null);
                    return;
                }
                double f = fraction(j);
                bar.setProgress(j.state() == JobState.PREFLIGHT ? -1 : f);
                bar.getStyleClass().removeAll("success", "error", "warn");
                if (j.state() == JobState.FAILED) bar.getStyleClass().add("error");
                else if (j.state() == JobState.COMPLETED || j.state() == JobState.RESTORED) bar.getStyleClass().add("success");
                else if (j.state() == JobState.PAUSED || j.state() == JobState.RECOVERY_REQUIRED) bar.getStyleClass().add("warn");
                text.setText(Fmt.percent(Math.round(f * 1000), 1000));
                setGraphic(box);
            }
        });
        table.getColumns().add(state);
        table.getColumns().add(progress);
        table.getColumns().add(UI.numCol("Items", 100, j -> j.doneItems() + " / " + j.totalItems()
                + (j.failedItems() > 0 ? "  (" + j.failedItems() + " failed)" : "")));
        table.getColumns().add(UI.numCol("Data", 150, j -> Fmt.size(j.doneBytes()) + " / " + Fmt.size(j.totalBytes())));
        table.getColumns().add(UI.col("Started", 160, j -> Fmt.iso(j.startedUtc() != null ? j.startedUtc()
                : j.createdUtc())));
        table.getColumns().add(UI.numCol("Elapsed", 90, j -> {
            long ms = Fmt.elapsedMs(j.startedUtc(), j.finishedUtc(), System.currentTimeMillis());
            return ms < 0 ? Fmt.DASH : Fmt.duration(ms);
        }));
        table.getColumns().add(UI.col("Origin", 90, j -> Fmt.title(j.origin().name())));
        table.getColumns().add(UI.col("Message", 420, j -> j.cancelRequested() && !j.state().terminal()
                ? "Stopping after current operation..." : j.message()));
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            updateButtons();
            showDetail();
        });
    }

    private static String kindOf(JobState s) {
        return switch (s) {
            case COMPLETED, RESTORED -> "ok";
            case FAILED -> "error";
            case RECOVERY_REQUIRED, PAUSED, CANCELLED -> "warn";
            case RUNNING, PREFLIGHT, VERIFYING, COMMITTING -> "info";
            default -> "neutral";
        };
    }

    private double fraction(Job j) {
        if (j.state() == JobState.COMPLETED || j.state() == JobState.RESTORED) return 1;
        double live = liveBytes(j);
        if (j.totalBytes() > 0) return Math.min(1, (j.doneBytes() + live) / (double) j.totalBytes());
        return j.totalItems() <= 0 ? 0 : Math.min(1, j.doneItems() / (double) j.totalItems());
    }

    private long liveBytes(Job j) {
        if (j.state().terminal()) return 0;
        return ctx.engine().jobManager().live(j.id()).map(JobManager.LiveProgress::fileDone).orElse(0L);
    }

    // ------------------------------------------------------------------ loading

    @Override
    protected CompletableFuture<Void> load() {
        return Background.supply(() -> ctx.engine().jobs().jobs(200)).thenAccept(list -> {
            all = list;
            applyFilter();
        });
    }

    private void applyFilter() {
        Job sel = selectedJob();
        StateFilter f = filter.getValue();
        List<Job> shown = new ArrayList<>();
        for (Job j : all) if (f == null || f.states() == null || f.states().contains(j.state())) shown.add(j);
        rows.setAll(shown);
        if (sel != null) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id() == sel.id()) {
                    table.getSelectionModel().select(i);
                    break;
                }
            }
        }
        updateButtons();
        showDetail();
    }

    private Job selectedJob() {
        return table.getSelectionModel().getSelectedItem();
    }

    private void updateButtons() {
        Job j = selectedJob();
        boolean active = j != null && ACTIVE.contains(j.state());
        pauseBtn.setDisable(j == null || !active || j.state() == JobState.PAUSED);
        resumeBtn.setDisable(j == null || j.state() != JobState.PAUSED);
        cancelBtn.setDisable(j == null || !active || j.cancelRequested());
        inspectBtn.setDisable(j == null);
        boolean retryable = j != null && (j.state() == JobState.FAILED || j.state() == JobState.CANCELLED
                || j.failedItems() > 0) && j.kind() == JobKind.OPTIMIZE && j.method() != null
                && !ctx.engine().readOnly();
        retryBtn.setDisable(!retryable);
        UI.tooltip(retryBtn, retryable ? "Re-preview the files that did not finish"
                : "Available for optimization jobs that failed, were cancelled or had failed files");
        recoveryBtn.setDisable(j == null || j.kind() != JobKind.OPTIMIZE);
        UI.tooltip(recoveryBtn, "Compressed or archived files from this job can be restored in Recovery");
    }

    // ------------------------------------------------------------------ detail

    private void showDetail() {
        Job j = selectedJob();
        detail.getChildren().clear();
        if (j == null) {
            detail.getChildren().add(UI.muted("Select a job to see live progress, throughput and its message."));
            return;
        }
        detail.getChildren().add(UI.label("Job #" + j.id() + " - " + Fmt.title(j.kind().name())
                + (j.method() == null ? "" : " (" + j.method().label() + ")"), "h3"));
        if (j.message() != null && !j.message().isBlank()) {
            detail.getChildren().add(UI.wrapped(j.cancelRequested() && !j.state().terminal()
                    ? "Stopping after current operation..." : j.message(), "detail-value"));
        }
        var live = j.state().terminal() ? java.util.Optional.<JobManager.LiveProgress>empty()
                : ctx.engine().jobManager().live(j.id());
        if (live.isPresent()) {
            JobManager.LiveProgress lp = live.get();
            long elapsedNanos = System.nanoTime() - lp.startedNanos();
            detail.getChildren().add(UI.wrapped("Current file: " + lp.currentPath(), "detail-value"));
            detail.getChildren().add(UI.label("Stage: " + lp.stage() + " - " + Fmt.size(lp.fileDone()) + " of "
                    + Fmt.size(lp.fileTotal()) + "   Throughput: " + Fmt.throughput(lp.fileDone(), elapsedNanos),
                    "detail-value"));
        }
        long startMs = Fmt.elapsedMs(j.startedUtc(), null, System.currentTimeMillis());
        if (!j.state().terminal() && j.state() == JobState.RUNNING && startMs > 3000) {
            double done = j.doneBytes() + liveBytes(j);
            double rate = done / (startMs / 1000.0);
            Fmt.eta(j.totalBytes() - (long) done, rate).ifPresentOrElse(
                    s -> detail.getChildren().add(UI.label("Estimated time remaining: " + s
                            + " (based on throughput so far)", "detail-value")),
                    () -> detail.getChildren().add(UI.label("Estimated time remaining: not available yet",
                            "muted")));
        }
        detail.getChildren().add(UI.label("Created " + Fmt.iso(j.createdUtc()) + (j.finishedUtc() != null
                ? "   Finished " + Fmt.iso(j.finishedUtc()) : ""), "muted", "small"));
    }

    // ------------------------------------------------------------------ actions

    private void control(boolean pause) {
        Job j = selectedJob();
        if (j == null) return;
        Background.run(() -> {
            if (pause) ctx.engine().jobManager().pause(j.id());
            else ctx.engine().jobManager().resume(j.id());
        }).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot change the job", Background.message(err));
            refresh();
        });
    }

    private void cancelSelected() {
        Job j = selectedJob();
        if (j == null) return;
        if (!Dialogs.confirm(owner(), "Cancel job", "Cancel job #" + j.id() + "?",
                "The job stops after the current operation finishes safely. Files already processed stay as they"
                        + " are and remain restorable.", "Cancel job")) return;
        Background.run(() -> ctx.engine().jobManager().cancel(j.id())).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot cancel", Background.message(err));
            else toast("Stopping after current operation...");
            refresh();
        });
    }

    private void retry(Job j) {
        if (j == null || j.method() == null) return;
        Background.supply(() -> {
            List<Long> ids = new ArrayList<>();
            for (JobRepository.Operation op : ctx.engine().jobs().operations(j.id())) {
                if (op.itemId() != null && !"DONE".equals(op.state())) ids.add(op.itemId());
            }
            return ids;
        }).whenComplete((ids, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot retry", Background.message(err));
            else if (ids.isEmpty()) Dialogs.info(owner(), "Retry", "Nothing to retry",
                    "Every operation of this job finished, or no failed files were recorded.");
            else PlanPreviewDialog.show(ctx, owner(), ids, j.method());
        });
    }

    private void inspect(Job j) {
        if (j == null) return;
        Stage st = new Stage();
        st.initOwner(owner());
        st.initModality(Modality.NONE);
        st.setTitle("Job #" + j.id() + " - operations");
        TableView<JobRepository.Operation> ops = new TableView<>();
        ops.setPlaceholder(UI.label("No per-file operations were recorded.", "muted"));
        ops.getColumns().add(UI.col("File", 420, o -> o.path()));
        ops.getColumns().add(UI.col("Action", 90, o -> Fmt.title(o.action())));
        ops.getColumns().add(UI.col("State", 110, o -> Fmt.title(o.state())));
        ops.getColumns().add(UI.col("Verification", 220, o -> o.verification()));
        ops.getColumns().add(UI.col("Recovery", 200, o -> o.recovery()));
        ops.getColumns().add(UI.col("Error", 300, o -> o.error()));
        TextArea journal = new TextArea("Select an operation to see its journal.");
        journal.setEditable(false);
        journal.getStyleClass().add("mono");
        journal.setPrefHeight(200);
        ops.getSelectionModel().selectedItemProperty().addListener((obs, a, b) -> {
            if (b == null) return;
            Background.supply(() -> ctx.engine().jobs().journalOf(b.id())).whenComplete((list, err) -> {
                if (err != null) {
                    journal.setText("Cannot read the journal: " + Background.message(err));
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("Operation ").append(b.uuid()).append("\n").append(b.path()).append("\n\n");
                if (b.beforeState() != null) sb.append("Before: ").append(b.beforeState()).append('\n');
                if (b.afterState() != null) sb.append("After:  ").append(b.afterState()).append("\n\n");
                for (JobRepository.JournalEntry je : list) {
                    sb.append(Fmt.iso(je.atUtc())).append("  ").append(je.step());
                    if (je.data() != null && !je.data().isBlank()) sb.append("  ").append(je.data());
                    sb.append('\n');
                }
                journal.setText(sb.toString());
            });
        });
        Background.supply(() -> ctx.engine().jobs().operations(j.id())).whenComplete((list, err) -> {
            if (err != null) Dialogs.error(st, "Cannot read operations", Background.message(err));
            else ops.setItems(FXCollections.observableArrayList(list));
        });
        SplitPane sp = new SplitPane(ops, journal);
        sp.setOrientation(javafx.geometry.Orientation.VERTICAL);
        HBox head = UI.hbox(8, UI.label("Job #" + j.id() + " " + Fmt.title(j.state().name()), "h3"),
                UI.label(j.message() == null ? "" : j.message(), "muted"));
        VBox root = new VBox(8, head, sp);
        root.setPadding(new Insets(12));
        VBox.setVgrow(sp, Priority.ALWAYS);
        Scene scene = new Scene(new StackPane(root), 1040, 640);
        Theme.register(scene);
        st.setScene(scene);
        st.show();
    }
}
