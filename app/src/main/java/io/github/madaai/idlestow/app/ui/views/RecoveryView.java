package io.github.madaai.idlestow.app.ui.views;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.Actions;
import io.github.madaai.idlestow.app.ui.Dialogs;
import io.github.madaai.idlestow.app.ui.Icons;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.Fmt;
import io.github.madaai.idlestow.core.db.RecoveryRepository.Record;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.jobs.CrashRecovery;
import io.github.madaai.idlestow.core.model.Enums.RecoveryKind;
import io.github.madaai.idlestow.core.model.Enums.RecoveryStatus;
import io.github.madaai.idlestow.core.util.Json;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Everything that can be reversed: native compression, archive copies and archive replacements. */
public final class RecoveryView extends BaseView {
    private final ObservableList<Record> rows = FXCollections.observableArrayList();
    private final TableView<Record> table = new TableView<>(rows);
    private final CheckBox showClosed = new CheckBox("Show restored / removed");
    private final VBox summary = new VBox(8);
    private final Label why = UI.wrapped("", "muted", "small");
    private final Button decompressBtn = UI.button("Decompress", Icons.EXPLORER);
    private final Button restoreBtn = UI.button("Restore in place...", Icons.RECOVERY, "primary");
    private final Button verifyBtn = UI.button("Verify integrity", Icons.SHIELD);
    private final Button reclaimBtn = UI.button("Reclaim space now...", Icons.BOLT, "danger");
    private final Button deleteCopyBtn = UI.button("Delete compressed copy...", Icons.X);
    private final Button manifestBtn = UI.button("Open manifest", Icons.FILE);
    private Long pendingSelect;

    public RecoveryView(AppContext ctx) {
        super(ctx);
        configureTable();
        showClosed.selectedProperty().addListener((o, a, b) -> refresh());
        decompressBtn.setOnAction(e -> decompress());
        restoreBtn.setOnAction(e -> restore());
        verifyBtn.setOnAction(e -> verify());
        reclaimBtn.setOnAction(e -> reclaim());
        deleteCopyBtn.setOnAction(e -> deleteCopy());
        manifestBtn.setOnAction(e -> manifest());
        FlowPane bar = new FlowPane(8, 6);
        bar.getChildren().addAll(decompressBtn, restoreBtn, verifyBtn, reclaimBtn, deleteCopyBtn, manifestBtn,
                showClosed);
        VBox root = new VBox(10, summary, bar, why, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        root.setPadding(new Insets(14));
        setContent(root);
        updateButtons();
    }

    @Override
    public String title() {
        return "Recovery";
    }

    @Override
    protected Set<IdleStowEngine.Event> events() {
        return Set.of(IdleStowEngine.Event.JOBS, IdleStowEngine.Event.RECOVERY, IdleStowEngine.Event.ANALYSIS);
    }

    /** Selects a record once loaded (used from other views). */
    public void select(Long id) {
        pendingSelect = id;
        if (id != null) refresh();
    }

    private void configureTable() {
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(UI.emptyState(Icons.RECOVERY, "Nothing to recover",
                "When IdleStow compresses or archives files, a recovery record appears here so everything can be"
                        + " restored."));
        table.getColumns().add(UI.col("Kind", 130, r -> kindLabel(r.kind())));
        table.getColumns().add(UI.col("Status", 120, r -> Fmt.title(r.status().name())));
        table.getColumns().add(UI.col("Original file", 430, Record::originalPath));
        table.getColumns().add(UI.col("Archive", 380, r -> r.archivePath() == null ? "" : r.archivePath()));
        table.getColumns().add(UI.numCol("Archive size", 100, r -> Fmt.size(r.archiveSize())));
        table.getColumns().add(UI.numCol("Retained original", 120, r -> r.retainedOriginalPath() == null ? Fmt.DASH
                : Fmt.size(r.retainedSize())));
        table.getColumns().add(UI.col("Keep until", 160, r -> Fmt.iso(r.retainUntilUtc())));
        table.getColumns().add(UI.col("Last verified", 160, r -> Fmt.iso(r.lastVerifiedUtc())));
        table.getColumns().add(UI.col("Notes", 380, Record::note));
        table.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<Record>) c -> updateButtons());
    }

    private static String kindLabel(RecoveryKind k) {
        return switch (k) {
            case NATIVE -> "Native compression";
            case ARCHIVE_COPY -> "Archive copy";
            case ARCHIVE_REPLACE -> "Archive replace";
        };
    }

    @Override
    protected CompletableFuture<Void> load() {
        boolean closed = showClosed.isSelected();
        return Background.supply(() -> {
            IdleStowEngine e = ctx.engine();
            return new Object[]{e.recoveryRecords().list(closed, 500), e.recoveryRecords().retainedBytes(),
                    e.recoveryRecords().countNeedingAttention(), e.startupRecovery().orElse(null)};
        }).thenAccept(res -> {
            @SuppressWarnings("unchecked")
            List<Record> list = (List<Record>) res[0];
            Set<Long> keep = new java.util.HashSet<>();
            for (Record r : table.getSelectionModel().getSelectedItems()) keep.add(r.id());
            if (pendingSelect != null) keep.add(pendingSelect);
            rows.setAll(list);
            for (int i = 0; i < rows.size(); i++) {
                if (keep.contains(rows.get(i).id())) table.getSelectionModel().select(i);
            }
            pendingSelect = null;
            renderSummary((Long) res[1], (Long) res[2], (CrashRecovery.Report) res[3]);
            updateButtons();
        });
    }

    private void renderSummary(long retained, long attention, CrashRecovery.Report report) {
        summary.getChildren().clear();
        HBox cards = new HBox(14, UI.kpi("Storage overhead", UI.label(Fmt.size(retained)),
                "Retained originals still on disk. Reclaim them once you trust the archives.", "Measured", "measured"),
                UI.kpi("Need attention", UI.label(Long.toString(attention)), attention == 0
                        ? "All recovery records are healthy" : "Review the highlighted records below", null, null));
        summary.getChildren().add(cards);
        if (attention > 0) {
            summary.getChildren().add(UI.banner(Icons.WARNING, attention + " record(s) are in review, damaged or"
                    + " missing. Nothing was deleted. Verify integrity or restore from the remaining copy.", "warn"));
        }
        if (report != null && report.inspected() > 0) {
            summary.getChildren().add(UI.banner(Icons.INFO, "Startup recovery checked " + report.inspected()
                    + " interrupted operation(s): " + report.completed() + " completed, " + report.rolledBack()
                    + " rolled back, " + report.review() + " need review. " + String.join(" ", report.notes()),
                    report.review() > 0 ? "warn" : "info"));
        }
    }

    // ------------------------------------------------------------------ enablement

    private List<Record> sel() {
        return new ArrayList<>(table.getSelectionModel().getSelectedItems());
    }

    private static boolean open(Record r) {
        return r.status() != RecoveryStatus.RESTORED && r.status() != RecoveryStatus.REMOVED;
    }

    private void updateButtons() {
        List<Record> s = sel();
        boolean ro = ctx.engine().readOnly();
        boolean any = !s.isEmpty();
        boolean single = s.size() == 1;
        boolean nat = any && s.stream().allMatch(r -> r.kind() == RecoveryKind.NATIVE && open(r) && r.itemId() != null);
        boolean arch = single && s.get(0).kind() != RecoveryKind.NATIVE && open(s.get(0));
        boolean verifiable = any && s.stream().allMatch(r -> r.archivePath() != null && open(r));
        boolean reclaim = any && s.stream().allMatch(r -> r.status() == RecoveryStatus.ACTIVE
                && r.retainedOriginalPath() != null && r.kind() == RecoveryKind.ARCHIVE_REPLACE);
        boolean copy = single && s.get(0).kind() == RecoveryKind.ARCHIVE_COPY && open(s.get(0));
        decompressBtn.setDisable(ro || !nat);
        restoreBtn.setDisable(ro || !arch);
        restoreBtn.setText(single && s.get(0).kind() == RecoveryKind.ARCHIVE_COPY ? "Extract to another folder..."
                : "Restore...");
        verifyBtn.setDisable(!verifiable || !ctx.workerAvailable());
        reclaimBtn.setDisable(ro || !reclaim);
        deleteCopyBtn.setDisable(ro || !copy);
        manifestBtn.setDisable(!(single && s.get(0).manifestPath() != null));
        why.setText(reason(s, ro, nat, arch, verifiable, reclaim));
    }

    private String reason(List<Record> s, boolean ro, boolean nat, boolean arch, boolean verifiable, boolean reclaim) {
        if (s.isEmpty()) return "Select a record to see which actions are available.";
        if (ro) return "Read-only mode: nothing can be changed.";
        List<String> out = new ArrayList<>();
        if (!nat) out.add("Decompress needs only natively compressed, open records.");
        if (!arch) out.add("Restore needs exactly one archive record that is still open.");
        if (!verifiable) out.add(ctx.workerAvailable() ? "Verify needs records that have an archive."
                : "Verification needs the Python worker, which is unavailable.");
        if (!reclaim) out.add("Reclaim space is only for archive replacements whose original is still retained.");
        return String.join(" ", out);
    }

    // ------------------------------------------------------------------ actions

    private void decompress() {
        List<Record> list = sel();
        if (!Dialogs.confirm(owner(), "Decompress", "Decompress " + Fmt.plural(list.size(), "file") + "?",
                "The files are stored uncompressed again and use more disk space.", "Decompress")) return;
        Background.supply(() -> {
            Long last = null;
            for (Record r : list) last = ctx.engine().restore(r.id(), null);
            return last;
        }).whenComplete((id, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot decompress", Background.message(err));
            else Actions.jobStarted(ctx, id, "Decompression");
        });
    }

    private void restore() {
        List<Record> s = sel();
        if (s.size() == 1) Actions.restore(ctx, owner(), s.get(0));
    }

    private void verify() {
        List<Long> ids = sel().stream().map(Record::id).toList();
        Background.supply(() -> ctx.engine().verifyRecovery(ids)).whenComplete((id, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot verify", Background.message(err));
            else Actions.jobStarted(ctx, id, "Integrity verification");
        });
    }

    private void reclaim() {
        List<Record> list = sel();
        long bytes = list.stream().mapToLong(r -> r.retainedSize() == null ? 0 : r.retainedSize()).sum();
        boolean ok = Dialogs.confirm(owner(), "Reclaim space now", "Remove " + Fmt.plural(list.size(),
                        "retained original") + " (" + Fmt.size(bytes) + ")?",
                "After this, the archive becomes the ONLY copy of each file. IdleStow re-verifies every archive first"
                        + " and refuses if anything is wrong, but IdleStow is not a backup: keep your own backups of"
                        + " anything important.\n\nThis cannot be undone.", "Remove retained originals");
        if (!ok) return;
        List<Long> ids = list.stream().map(Record::id).toList();
        Background.supply(() -> ctx.engine().reclaimRetained(ids)).whenComplete((id, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot reclaim", Background.message(err));
            else Actions.jobStarted(ctx, id, "Reclaiming space");
        });
    }

    private void deleteCopy() {
        List<Record> s = sel();
        if (s.size() != 1) return;
        Record r = s.get(0);
        if (!Dialogs.confirm(owner(), "Delete compressed copy", "Delete the compressed copy?",
                "Only the compressed copy (" + r.archivePath() + ") is removed. Your original file is untouched.",
                "Delete copy")) return;
        Background.supply(() -> ctx.engine().deleteCompressedCopy(r.id())).whenComplete((msg, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot delete the copy", Background.message(err));
            else toast(msg);
            refresh();
        });
    }

    private void manifest() {
        List<Record> s = sel();
        if (s.size() != 1) return;
        Path p = Path.of(s.get(0).manifestPath());
        Background.supply(() -> {
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            try {
                return Json.pretty(Json.MAPPER.readTree(raw));
            } catch (Exception e) {
                return raw;
            }
        }).whenComplete((text, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot open the manifest", Background.message(err));
            else Dialogs.textView(owner(), "Manifest - " + p.getFileName(), text);
        });
    }
}
