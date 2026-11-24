package io.github.madaai.idlestow.app.ui.views;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.Actions;
import io.github.madaai.idlestow.app.ui.Dialogs;
import io.github.madaai.idlestow.app.ui.Icons;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.Fmt;
import io.github.madaai.idlestow.app.util.HistoryExport;
import io.github.madaai.idlestow.core.db.HistoryRepository.Entry;
import io.github.madaai.idlestow.core.db.RecoveryRepository;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.model.Enums.RecoveryKind;
import io.github.madaai.idlestow.core.model.Enums.RecoveryStatus;
import io.github.madaai.idlestow.core.util.PathUtil;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Searchable optimization history with CSV / JSON export and restore for entries that can still be reversed. */
public final class HistoryView extends BaseView {
    private static final int PAGE = 500;

    private final ObservableList<Entry> rows = FXCollections.observableArrayList();
    private final TableView<Entry> table = new TableView<>(rows);
    private final TextField search = new TextField();
    private final ComboBox<String> outcome = new ComboBox<>(FXCollections.observableArrayList("All outcomes",
            "SUCCEEDED", "NO_BENEFIT", "RESTORED", "FAILED"));
    private final ToggleButton oldestFirst = new ToggleButton("Oldest first");
    private final Button loadMore = UI.button("Load more", Icons.CHEVRON_DOWN);
    private final Button csvBtn = UI.button("Export CSV...", Icons.DOWNLOAD);
    private final Button jsonBtn = UI.button("Export JSON...", Icons.DOWNLOAD);
    private final Button restoreBtn = UI.button("Restore...", Icons.RECOVERY);
    private final Label count = UI.label("", "muted");
    private final VBox detail = new VBox(8);
    private final PauseTransition delay = new PauseTransition(Duration.millis(320));
    private boolean allLoaded;
    private int seq;
    private RecoveryRepository.Record restorable;

    public HistoryView(AppContext ctx) {
        super(ctx);
        search.setPromptText("Search by file path");
        search.setPrefWidth(320);
        outcome.setValue("All outcomes");
        search.textProperty().addListener((o, a, b) -> delay.playFromStart());
        delay.setOnFinished(e -> refresh());
        outcome.valueProperty().addListener((o, a, b) -> refresh());
        oldestFirst.selectedProperty().addListener((o, a, b) -> refresh());
        oldestFirst.setTooltip(UI.tip("Sort by date: off = newest first"));
        loadMore.setOnAction(e -> loadPage(false));
        csvBtn.setOnAction(e -> export(true));
        jsonBtn.setOnAction(e -> export(false));
        restoreBtn.setOnAction(e -> restore());
        restoreBtn.setDisable(true);
        configureTable();

        FlowPane bar = new FlowPane(8, 6);
        bar.getChildren().addAll(search, outcome, oldestFirst, csvBtn, jsonBtn, restoreBtn);
        detail.setPadding(new Insets(12));
        SplitPane split = new SplitPane(table, detail);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.7);
        VBox.setVgrow(split, Priority.ALWAYS);
        VBox root = new VBox(10, bar, count, split, loadMore);
        root.setPadding(new Insets(14));
        loadMore.setVisible(false);
        loadMore.setManaged(false);
        setContent(root);
        showDetail();
    }

    @Override
    public String title() {
        return "History";
    }

    @Override
    public boolean focusSearch() {
        search.requestFocus();
        search.selectAll();
        return true;
    }

    /** Called from Explorer ("View history") to show one path. */
    public void filterByPath(String path) {
        search.setText(path == null ? "" : path);
        outcome.setValue("All outcomes");
    }

    @Override
    protected Set<IdleStowEngine.Event> events() {
        return Set.of(IdleStowEngine.Event.JOBS, IdleStowEngine.Event.RECOVERY);
    }

    private void configureTable() {
        table.setPlaceholder(UI.emptyState(Icons.HISTORY, "No history yet",
                "Every optimization, restore and verification IdleStow performs is recorded here, with its"
                        + " measured result. Nothing has happened yet."));
        table.getColumns().add(UI.col("Time", 165, e -> Fmt.iso(e.atUtc())));
        table.getColumns().add(UI.col("Action", 100, e -> Fmt.title(e.action())));
        table.getColumns().add(UI.col("Outcome", 110, e -> Fmt.title(e.outcome())));
        table.getColumns().add(UI.col("Method", 120, e -> Fmt.title(e.method())));
        table.getColumns().add(UI.col("File", 460, Entry::path));
        table.getColumns().add(UI.numCol("Size", 90, e -> Fmt.size(e.logicalSize())));
        table.getColumns().add(UI.numCol("Estimated", 100, e -> Fmt.estimate(e.estSavings())));
        table.getColumns().add(UI.numCol("Actual savings", 110, e -> e.actualSavings() == null ? Fmt.DASH
                : Fmt.signedSize(e.actualSavings())));
        table.getColumns().add(UI.col("Origin", 100, e -> Fmt.title(e.origin())));
        table.getColumns().add(UI.col("Mode", 100, e -> Fmt.title(e.authorizationMode())));
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            showDetail();
            findRestorable(b);
        });
    }

    @Override
    protected CompletableFuture<Void> load() {
        return loadPage(true);
    }

    private CompletableFuture<Void> loadPage(boolean reset) {
        int mySeq = ++seq;
        String text = search.getText();
        String oc = "All outcomes".equals(outcome.getValue()) ? null : outcome.getValue();
        boolean asc = oldestFirst.isSelected();
        int offset = reset ? 0 : rows.size();
        return Background.supply(() -> ctx.engine().history().search(text, oc, asc, offset, PAGE))
                .thenAccept(list -> {
                    if (mySeq != seq) return;
                    if (reset) rows.setAll(list);
                    else rows.addAll(list);
                    allLoaded = list.size() < PAGE;
                    loadMore.setVisible(!allLoaded);
                    loadMore.setManaged(!allLoaded);
                    count.setText(rows.isEmpty() ? "" : rows.size() + (allLoaded ? " records" : " records loaded"));
                    csvBtn.setDisable(rows.isEmpty());
                    jsonBtn.setDisable(rows.isEmpty());
                });
    }

    private void showDetail() {
        detail.getChildren().clear();
        Entry e = table.getSelectionModel().getSelectedItem();
        if (e == null) {
            detail.getChildren().add(UI.muted("Select a record to see the verification result, recovery information"
                    + " and any error."));
            return;
        }
        detail.getChildren().add(UI.label(Fmt.title(e.action()) + " - " + Fmt.title(e.outcome()), "h3"));
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(4);
        int r = 0;
        r = row(g, r, "File", e.path());
        r = row(g, r, "Time", Fmt.iso(e.atUtc()));
        r = row(g, r, "Method", Fmt.title(e.method()));
        r = row(g, r, "Requested by", Fmt.title(e.origin()) + " (mode " + Fmt.title(e.authorizationMode()) + ")");
        r = row(g, r, "Original size", Fmt.size(e.logicalSize()));
        r = row(g, r, "On disk before / after", Fmt.size(e.beforePhysical()) + "  ->  " + Fmt.size(e.afterPhysical()));
        r = row(g, r, "Estimated savings", Fmt.estimate(e.estSavings()) + " (estimate)");
        r = row(g, r, "Actual savings", e.actualSavings() == null ? Fmt.DASH : Fmt.signedSize(e.actualSavings())
                + " (measured)");
        r = row(g, r, "Verification", e.verification());
        r = row(g, r, "How to undo", e.recovery());
        if (e.error() != null && !e.error().isBlank()) row(g, r, "Note / error", e.error());
        detail.getChildren().add(g);
    }

    private static int row(GridPane g, int r, String k, String v) {
        g.add(UI.label(k, "detail-key"), 0, r);
        Label val = UI.wrapped(v == null || v.isBlank() ? Fmt.DASH : v, "detail-value");
        g.add(val, 1, r);
        GridPane.setHgrow(val, Priority.ALWAYS);
        return r + 1;
    }

    // ------------------------------------------------------------------ restore

    private void findRestorable(Entry e) {
        restorable = null;
        restoreBtn.setDisable(true);
        UI.tooltip(restoreBtn, "Select a record that has an active recovery record");
        if (e == null || e.path() == null || ctx.engine().readOnly()) return;
        String key = PathUtil.key(Path.of(e.path()));
        Background.supply(() -> {
            for (RecoveryRepository.Record rec : ctx.engine().recoveryRecords().list(false, 5000)) {
                boolean active = rec.status() == RecoveryStatus.ACTIVE || rec.status() == RecoveryStatus.ORIGINAL_PURGED;
                if (active && rec.originalPath() != null && PathUtil.key(Path.of(rec.originalPath())).equals(key)) {
                    return Optional.of(rec);
                }
            }
            return Optional.<RecoveryRepository.Record>empty();
        }).whenComplete((rec, err) -> {
            if (err != null || table.getSelectionModel().getSelectedItem() != e) return;
            if (rec.isPresent()) {
                restorable = rec.get();
                restoreBtn.setDisable(false);
                UI.tooltip(restoreBtn, "Restore this file from its recovery record");
            }
        });
    }

    private void restore() {
        RecoveryRepository.Record rec = restorable;
        if (rec == null) return;
        if (rec.kind() == RecoveryKind.NATIVE) {
            if (!Dialogs.confirm(owner(), "Decompress", "Decompress this file?",
                    "The file is stored uncompressed again and uses more disk space.", "Decompress")) return;
            Background.supply(() -> ctx.engine().restore(rec.id(), null)).whenComplete((id, err) -> {
                if (err != null) Dialogs.error(owner(), "Cannot decompress", Background.message(err));
                else Actions.jobStarted(ctx, id, "Decompression");
            });
        } else {
            Actions.restore(ctx, owner(), rec);
        }
    }

    // ------------------------------------------------------------------ export

    private void export(boolean csv) {
        var target = Dialogs.saveFile(owner(), csv ? "Export history as CSV" : "Export history as JSON",
                "idlestow-history." + (csv ? "csv" : "json"), csv ? "CSV (*.csv)" : "JSON (*.json)",
                csv ? "*.csv" : "*.json");
        if (target.isEmpty()) return;
        String text = search.getText();
        String oc = "All outcomes".equals(outcome.getValue()) ? null : outcome.getValue();
        boolean asc = oldestFirst.isSelected();
        Path file = target.get();
        Background.supply(() -> {
            List<Entry> all = ctx.engine().history().search(text, oc, asc, 0, 1_000_000);
            if (csv) HistoryExport.writeCsv(file, all);
            else HistoryExport.writeJson(file, all);
            return all.size();
        }).whenComplete((n, err) -> {
            if (err != null) Dialogs.error(owner(), "Export failed", Background.message(err));
            else toast("Exported " + Fmt.plural(n, "record") + " to " + file);
        });
    }
}
