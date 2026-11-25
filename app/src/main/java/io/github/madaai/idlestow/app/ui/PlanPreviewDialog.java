package io.github.madaai.idlestow.app.ui;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.Fmt;
import io.github.madaai.idlestow.core.engine.PlanPreview;
import io.github.madaai.idlestow.core.model.Enums.ArchiveFormat;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.Origin;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The safety checkpoint before anything is changed: shows exactly which files will change, which stay
 * untouched (and why), the estimated savings, warnings and how to undo. Nothing is executed without this
 * review (manual optimization) and the user can uncheck individual files.
 */
public final class PlanPreviewDialog {

    /** Method choice with availability and the reason when unavailable. */
    private record Option(Method method, boolean enabled, String reason) {
        @Override
        public String toString() {
            return method.label() + (enabled ? "" : "  (unavailable: " + reason + ")");
        }
    }

    private static final class Row {
        final PlanPreview.Item item;
        final BooleanProperty selected = new SimpleBooleanProperty(true);

        Row(PlanPreview.Item item) {
            this.item = item;
        }
    }

    private final AppContext ctx;
    private final List<Long> itemIds;
    private final Stage stage = new Stage();
    private final ComboBox<Option> methodBox = new ComboBox<>();
    private final TableView<Row> changeTable = new TableView<>();
    private final TableView<PlanPreview.Item> untouchedTable = new TableView<>();
    private final Label summary = UI.label("", "h3");
    private final Label untouchedTitle = UI.label("", "h3");
    private final VBox notes = new VBox(8);
    private final CheckBox replaceConfirm = new CheckBox(
            "I understand the files will be replaced by archives and can only be opened again after restoring them.");
    private final Button execute = UI.button("Optimize", Icons.COMPRESS, "primary");
    private final ProgressIndicator busy = new ProgressIndicator();
    private final Label recoveryNote = UI.wrapped("", "muted");
    private PlanPreview current;
    private int requestSeq;
    private boolean suppressMethodEvents;

    private PlanPreviewDialog(AppContext ctx, List<Long> itemIds) {
        this.ctx = ctx;
        this.itemIds = List.copyOf(itemIds);
    }

    public static void show(AppContext ctx, Window owner, List<Long> itemIds, Method initial) {
        if (itemIds.isEmpty()) return;
        PlanPreviewDialog d = new PlanPreviewDialog(ctx, itemIds);
        d.build(owner);
        d.loadOptions(initial);
        d.stage.show();
    }

    // ------------------------------------------------------------------ construction

    private void build(Window owner) {
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("Plan preview - IdleStow");
        stage.setMinWidth(820);
        stage.setMinHeight(560);

        Label title = UI.label("Review before optimizing", "h2");
        Label sub = UI.wrapped("Nothing has been changed yet. Optimizing never deletes a file: compression is"
                + " verified and can be reversed. Uncheck any file you want to leave alone.", "muted");

        methodBox.setMaxWidth(Double.MAX_VALUE);
        methodBox.setPrefWidth(480);
        methodBox.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Option o, boolean empty) {
                super.updateItem(o, empty);
                setText(empty || o == null ? null : o.toString());
                setDisable(!empty && o != null && !o.enabled());
                setOpacity(!empty && o != null && !o.enabled() ? 0.5 : 1);
            }
        });
        methodBox.valueProperty().addListener((obs, a, b) -> {
            if (!suppressMethodEvents && b != null && b.enabled()) loadPreview(b.method());
        });
        busy.setMaxSize(22, 22);
        busy.setVisible(false);
        HBox methodRow = UI.hbox(10, UI.label("Method", "detail-key"), methodBox, busy);

        configureChangeTable();
        configureUntouchedTable();

        VBox changeCard = new VBox(8, UI.hbox(8, summary), changeTable);
        VBox.setVgrow(changeTable, Priority.ALWAYS);
        VBox untouchedCard = new VBox(8, untouchedTitle, untouchedTable);
        VBox.setVgrow(untouchedTable, Priority.ALWAYS);
        SplitPane split = new SplitPane(changeCard, untouchedCard);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.58);
        VBox.setVgrow(split, Priority.ALWAYS);

        replaceConfirm.setWrapText(true);
        replaceConfirm.setVisible(false);
        replaceConfirm.setManaged(false);
        replaceConfirm.selectedProperty().addListener((o, a, b) -> updateButtons());

        Button cancel = UI.button("Cancel", null, stage::close);
        cancel.setCancelButton(true);
        execute.setDefaultButton(false);
        execute.setOnAction(e -> runExecute());
        execute.setDisable(true);
        HBox buttons = UI.hbox(10, UI.spacer(), cancel, execute);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, title, sub, methodRow, split, notes, recoveryNote, replaceConfirm, buttons);
        root.setPadding(new Insets(18));
        Scene scene = new Scene(new StackPane(root), 1020, 760);
        Theme.register(scene);
        stage.setScene(scene);
    }

    private void configureChangeTable() {
        changeTable.setPlaceholder(UI.label("Nothing in this selection can be optimized with this method.", "muted"));
        changeTable.setEditable(true);
        changeTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        TableColumn<Row, Boolean> chk = new TableColumn<>();
        chk.setPrefWidth(46);
        chk.setEditable(true);
        chk.setSortable(false);
        chk.setCellValueFactory(cd -> cd.getValue().selected);
        chk.setCellFactory(CheckBoxTableCell.forTableColumn(chk));
        CheckBox all = new CheckBox();
        all.setSelected(true);
        all.setAccessibleText("Select or clear all files");
        all.setOnAction(e -> {
            for (Row r : changeTable.getItems()) r.selected.set(all.isSelected());
        });
        chk.setGraphic(all);
        changeTable.getColumns().add(chk);
        changeTable.getColumns().add(UI.col("Will change", 460, (Row r) -> r.item.path().toString()));
        changeTable.getColumns().add(UI.numCol("Size", 90, (Row r) -> Fmt.size(r.item.size())));
        changeTable.getColumns().add(UI.numCol("Est. savings", 110, (Row r) -> Fmt.estimate(r.item.estimatedSavings())));
        changeTable.getColumns().add(UI.col("Activity", 150, (Row r) -> r.item.activity()));
        changeTable.getColumns().add(UI.col("Confidence", 90, (Row r) -> Fmt.title(r.item.confidence())));
    }

    private void configureUntouchedTable() {
        untouchedTable.setPlaceholder(UI.label("Every selected file is eligible.", "muted"));
        untouchedTable.getColumns().add(UI.col("Will remain untouched", 460, (PlanPreview.Item i) -> i.path().toString()));
        TableColumn<PlanPreview.Item, String> reason = UI.col("Reason", 480, (PlanPreview.Item i) -> i.reason());
        untouchedTable.getColumns().add(reason);
        untouchedTable.getColumns().add(UI.numCol("Size", 90, (PlanPreview.Item i) -> Fmt.size(i.size())));
    }

    // ------------------------------------------------------------------ data

    private void loadOptions(Method initial) {
        busy.setVisible(true);
        Background.supply(() -> {
            Map<Method, Option> m = new EnumMap<>(Method.class);
            boolean nativeOk = ctx.engine().platform().nativeCompression().implemented();
            Set<ArchiveFormat> formats = null;
            String workerProblem = null;
            try {
                formats = ctx.engine().worker().supportedFormats();
            } catch (Exception e) {
                workerProblem = "optimization worker unavailable (" + Background.message(e) + ")";
            }
            boolean replaceAllowed = ctx.engine().settings().archiveReplaceEnabled();
            for (Method me : Method.values()) {
                if (me == Method.NATIVE) {
                    m.put(me, new Option(me, nativeOk && !ctx.engine().readOnly(), ctx.engine().readOnly()
                            ? "read-only mode" : "not supported on this platform"));
                } else if (ctx.engine().readOnly()) {
                    m.put(me, new Option(me, false, "read-only mode"));
                } else if (formats == null) {
                    m.put(me, new Option(me, false, workerProblem));
                } else if (!formats.contains(me.format())) {
                    m.put(me, new Option(me, false, me.format().wire() + " is not supported by this worker"));
                } else if (me.replacesOriginal() && !replaceAllowed) {
                    m.put(me, new Option(me, false, "enable archive replacement in Settings > Optimization"));
                } else {
                    m.put(me, new Option(me, true, ""));
                }
            }
            return new Object[]{m, ctx.engine().settings().preferredMethod()};
        }).whenComplete((res, err) -> {
            busy.setVisible(false);
            if (err != null) {
                Dialogs.error(stage, "Cannot prepare the preview", Background.message(err));
                return;
            }
            @SuppressWarnings("unchecked")
            Map<Method, Option> opts = (Map<Method, Option>) res[0];
            Method preferred = (Method) res[1];
            suppressMethodEvents = true;
            methodBox.setItems(FXCollections.observableArrayList(opts.values()));
            Option pick = null;
            for (Method cand : new Method[]{initial, preferred, Method.NATIVE, Method.ZIP_COPY}) {
                if (cand != null && opts.get(cand) != null && opts.get(cand).enabled()) {
                    pick = opts.get(cand);
                    break;
                }
            }
            suppressMethodEvents = false;
            if (pick == null) {
                execute.setDisable(true);
                notes.getChildren().setAll(UI.banner(Icons.WARNING, ctx.engine().readOnly()
                        ? "IdleStow is running in read-only mode: files are never changed."
                        : "No optimization method is available. " + ctx.workerNote(), "warn"));
                return;
            }
            methodBox.setValue(pick); // triggers the first preview
        });
    }

    private void loadPreview(Method method) {
        int seq = ++requestSeq;
        busy.setVisible(true);
        execute.setDisable(true);
        Background.supply(() -> ctx.engine().preview(itemIds, method)).whenComplete((p, err) -> {
            if (seq != requestSeq) return;
            busy.setVisible(false);
            if (err != null) {
                Dialogs.error(stage, "Cannot compute the preview", Background.message(err));
                return;
            }
            apply(p);
        });
    }

    private void apply(PlanPreview p) {
        current = p;
        List<Row> rows = new ArrayList<>();
        for (PlanPreview.Item i : p.willChange()) {
            Row r = new Row(i);
            r.selected.addListener((o, a, b) -> updateSummary());
            rows.add(r);
        }
        changeTable.setItems(FXCollections.observableArrayList(rows));
        untouchedTable.setItems(FXCollections.observableArrayList(p.untouched()));
        untouchedTitle.setText(p.untouched().size() + " file(s) will remain untouched");

        notes.getChildren().clear();
        if (p.changesAccessibility()) {
            notes.getChildren().add(UI.banner(Icons.WARNING, "Accessibility change: the selected files will be replaced by"
                    + " archives. Other programs cannot open them until you restore them (Recovery or Explorer).",
                    "warn"));
        }
        for (String w : p.warnings()) {
            notes.getChildren().add(UI.banner(Icons.INFO, w, "info"));
        }
        recoveryNote.setText("How to undo: " + p.recoveryNote());
        boolean replace = p.method().replacesOriginal();
        replaceConfirm.setVisible(replace);
        replaceConfirm.setManaged(replace);
        replaceConfirm.setSelected(false);
        updateSummary();
    }

    private List<Long> checkedIds() {
        List<Long> ids = new ArrayList<>();
        for (Row r : changeTable.getItems()) if (r.selected.get()) ids.add(r.item.itemId());
        return ids;
    }

    private void updateSummary() {
        long est = 0;
        int n = 0;
        for (Row r : changeTable.getItems()) {
            if (r.selected.get()) {
                n++;
                est += r.item.estimatedSavings();
            }
        }
        summary.setText(n + " of " + changeTable.getItems().size() + " files will change - estimated savings "
                + (est > 0 ? Fmt.APPROX + Fmt.size(est) : Fmt.DASH) + " (estimate, not guaranteed)");
        execute.setText("Optimize " + Fmt.plural(n, "file"));
        updateButtons();
    }

    private void updateButtons() {
        boolean any = current != null && !checkedIds().isEmpty();
        boolean needsConfirm = current != null && current.method().replacesOriginal();
        execute.setDisable(!any || (needsConfirm && !replaceConfirm.isSelected()) || ctx.engine().readOnly());
    }

    private void runExecute() {
        PlanPreview p = current;
        if (p == null) return;
        List<Long> ids = checkedIds();
        boolean confirmed = replaceConfirm.isSelected();
        execute.setDisable(true);
        busy.setVisible(true);
        Background.supply(() -> ctx.engine().execute(p, ids, Origin.MANUAL, confirmed)).whenComplete((jobId, err) -> {
            busy.setVisible(false);
            if (err != null) {
                Dialogs.error(stage, "Cannot start the optimization", Background.message(err));
                updateButtons();
                return;
            }
            stage.close();
            Actions.jobStarted(ctx, jobId, "Optimization of " + Fmt.plural(ids.size(), "file"));
            if (ctx.nav() != null) ctx.nav().show(ViewId.JOBS);
        });
    }
}
