package io.github.madaai.idlestow.app.ui.views;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.Dialogs;
import io.github.madaai.idlestow.app.ui.Icons;
import io.github.madaai.idlestow.app.ui.PlanPreviewDialog;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.Fmt;
import io.github.madaai.idlestow.core.db.CandidateRepository;
import io.github.madaai.idlestow.core.db.CandidateRepository.Candidate;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.model.Enums.AuthorizationMode;
import io.github.madaai.idlestow.core.model.Enums.CandidateStatus;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Recommendations grouped by folder with bulk approval, rejection, postponement and exclusion. */
public final class RecommendationsView extends BaseView {

    /** A table node: either a folder group (candidates = its files) or one recommendation. */
    private record Node(String folder, List<Candidate> candidates) {
        boolean group() {
            return folder != null;
        }

        Candidate leaf() {
            return candidates.get(0);
        }
    }

    private record StatusChoice(String label, List<CandidateStatus> statuses) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record SortChoice(String label, CandidateRepository.Sort sort) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record RootChoice(String label, Long id) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final TreeTableView<Node> table = new TreeTableView<>();
    private final VBox banner = new VBox();
    private final ComboBox<StatusChoice> statusBox = new ComboBox<>(FXCollections.observableArrayList(
            new StatusChoice("Pending", List.of(CandidateStatus.PENDING)),
            new StatusChoice("Postponed", List.of(CandidateStatus.POSTPONED)),
            new StatusChoice("Rejected", List.of(CandidateStatus.REJECTED)),
            new StatusChoice("Dismissed", List.of(CandidateStatus.DISMISSED)),
            new StatusChoice("All decisions", List.of())));
    private final ComboBox<SortChoice> sortBox = new ComboBox<>(FXCollections.observableArrayList(
            new SortChoice("Largest estimated savings", CandidateRepository.Sort.SAVINGS),
            new SortChoice("Longest inactive", CandidateRepository.Sort.IDLE),
            new SortChoice("Highest confidence", CandidateRepository.Sort.CONFIDENCE),
            new SortChoice("Path", CandidateRepository.Sort.PATH)));
    private final ComboBox<RootChoice> rootBox = new ComboBox<>();
    private final TextField extField = new TextField();
    private final CheckBox groupBox = new CheckBox("Group by folder");
    private final Label summary = UI.label("", "muted");
    private final Button approveBtn = UI.button("Approve...", Icons.CHECK, "primary");
    private final Button approveAllBtn = UI.button("Approve all shown...", Icons.CHECK);
    private final Button rejectBtn = UI.button("Reject", Icons.X);
    private final Button dismissBtn = UI.button("Dismiss", Icons.EYE);
    private final MenuButton postponeBtn = new MenuButton("Postpone");
    private final Button excludeBtn = UI.button("Exclude", Icons.LOCK);
    private final Button pinBtn = UI.button("Pin", Icons.PIN);
    private final VBox details = new VBox(6);
    private final StackPane tableHost = new StackPane();
    private List<Candidate> loaded = List.of();
    private boolean rootsFilled;

    public RecommendationsView(AppContext ctx) {
        super(ctx);
        statusBox.setValue(statusBox.getItems().get(0));
        sortBox.setValue(sortBox.getItems().get(0));
        rootBox.setValue(new RootChoice("All locations", null));
        rootBox.setItems(FXCollections.observableArrayList(rootBox.getValue()));
        extField.setPromptText("File type, e.g. pdf");
        extField.setPrefWidth(140);
        groupBox.setSelected(true);
        Runnable reload = () -> refresh();
        statusBox.valueProperty().addListener((o, a, b) -> reload.run());
        sortBox.valueProperty().addListener((o, a, b) -> reload.run());
        rootBox.valueProperty().addListener((o, a, b) -> {
            if (b != null) reload.run();
        });
        extField.setOnAction(e -> reload.run());
        groupBox.selectedProperty().addListener((o, a, b) -> render());

        configureTable();
        configureActions();

        HBox filters = UI.hbox(8, UI.label("Show", "detail-key"), statusBox, UI.label("Sort", "detail-key"), sortBox,
                UI.label("Location", "detail-key"), rootBox, extField, groupBox);
        FlowPane filterFlow = new FlowPane(8, 6);
        filterFlow.getChildren().addAll(filters);
        FlowPane actionFlow = new FlowPane(8, 6);
        actionFlow.getChildren().addAll(approveBtn, approveAllBtn, rejectBtn, dismissBtn, postponeBtn, excludeBtn,
                pinBtn);

        tableHost.getChildren().add(table);
        details.setPadding(new Insets(12));
        SplitPane split = new SplitPane(tableHost, details);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.74);
        VBox.setVgrow(split, Priority.ALWAYS);
        VBox root = new VBox(10, banner, filterFlow, actionFlow, summary, split);
        root.setPadding(new Insets(14));
        setContent(root);
        updateButtons();
        showDetails();
    }

    @Override
    public String title() {
        return "Recommendations";
    }

    @Override
    protected Set<IdleStowEngine.Event> events() {
        return Set.of(IdleStowEngine.Event.ANALYSIS, IdleStowEngine.Event.ROOTS, IdleStowEngine.Event.SETTINGS);
    }

    @Override
    public boolean focusSearch() {
        extField.requestFocus();
        return true;
    }

    // ------------------------------------------------------------------ table

    private void configureTable() {
        table.setShowRoot(false);
        table.setRoot(new TreeItem<>());
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TreeTableView.UNCONSTRAINED_RESIZE_POLICY);
        TreeTableColumn<Node, String> item = column("Item", 330, n -> n.group()
                ? n.folder() + "  (" + n.candidates().size() + " files)" : n.leaf().name());
        table.getColumns().add(item);
        table.setTreeColumn(item);
        table.getColumns().add(column("Size", 90, n -> Fmt.size(n.candidates().stream().mapToLong(Candidate::logicalSize).sum()), true));
        table.getColumns().add(column("Est. savings", 110, n -> Fmt.estimate(n.candidates().stream().mapToLong(Candidate::estSavings).sum()), true));
        table.getColumns().add(column("Confidence", 90, n -> n.group() ? "" : Fmt.title(n.leaf().confidence())));
        table.getColumns().add(column("Idle", 110, n -> n.group() ? "" : Fmt.idle(n.leaf().idleDays())));
        table.getColumns().add(column("Method", 200, n -> {
            if (!n.group()) return n.leaf().method().label();
            Set<String> m = new LinkedHashSet<>();
            for (Candidate c : n.candidates()) m.add(c.method().label());
            return m.size() == 1 ? m.iterator().next() : m.size() + " methods";
        }));
        table.getColumns().add(column("Policy", 140, n -> n.group() ? "" : n.leaf().policyRef()));
        table.getColumns().add(column("Auto-eligible", 100, n -> n.group() ? ""
                : n.leaf().autoEligible() ? "Yes" : "No"));
        table.getColumns().add(column("Why recommended", 420, n -> n.group() ? "" : n.leaf().reason()));
        table.getColumns().add(column("Warnings", 260, n -> n.group() ? "" : n.leaf().warnings()));
        table.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<TreeItem<Node>>) c -> {
                    updateButtons();
                    showDetails();
                });
        Label empty = UI.label("No recommendations match. Scan a folder (F5) or relax the filters.", "muted");
        table.setPlaceholder(empty);
    }

    private TreeTableColumn<Node, String> column(String title, double width, Function<Node, String> f) {
        return column(title, width, f, false);
    }

    private TreeTableColumn<Node, String> column(String title, double width, Function<Node, String> f, boolean right) {
        TreeTableColumn<Node, String> c = new TreeTableColumn<>(title);
        c.setPrefWidth(width);
        c.setSortable(false);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue() == null || cd.getValue().getValue() == null
                ? "" : f.apply(cd.getValue().getValue())));
        if (right) c.setStyle("-fx-alignment: CENTER-RIGHT;");
        return c;
    }

    // ------------------------------------------------------------------ loading

    @Override
    protected CompletableFuture<Void> load() {
        StatusChoice st = statusBox.getValue();
        SortChoice so = sortBox.getValue();
        RootChoice rc = rootBox.getValue();
        String ext = extField.getText() == null ? "" : extField.getText().trim().replaceFirst("^\\.", "");
        return Background.supply(() -> {
            IdleStowEngine e = ctx.engine();
            List<MonitoredRoot> roots = e.roots();
            List<Candidate> list = e.recommendations(st.statuses(), rc == null ? null : rc.id(), ext, so.sort(), 5000);
            return new Object[]{roots, list, e.settings().mode()};
        }).thenAccept(res -> {
            @SuppressWarnings("unchecked")
            List<MonitoredRoot> roots = (List<MonitoredRoot>) res[0];
            @SuppressWarnings("unchecked")
            List<Candidate> list = (List<Candidate>) res[1];
            fillRoots(roots);
            renderBanner((AuthorizationMode) res[2]);
            loaded = list;
            render();
        });
    }

    private void fillRoots(List<MonitoredRoot> roots) {
        RootChoice cur = rootBox.getValue();
        List<RootChoice> items = new ArrayList<>();
        items.add(new RootChoice("All locations", null));
        RootChoice reselect = items.get(0);
        for (MonitoredRoot r : roots) {
            RootChoice c = new RootChoice(r.displayName() + "  (" + r.path() + ")", r.id());
            items.add(c);
            if (cur != null && cur.id() != null && cur.id() == r.id()) reselect = c;
        }
        boolean same = rootBox.getItems().size() == items.size();
        for (int i = 0; same && i < items.size(); i++) same = items.get(i).equals(rootBox.getItems().get(i));
        if (!same) {
            rootBox.setItems(FXCollections.observableArrayList(items));
            rootBox.setValue(reselect);
        }
        rootsFilled = true;
    }

    private void renderBanner(AuthorizationMode mode) {
        banner.getChildren().clear();
        boolean manual = mode == AuthorizationMode.MANUAL;
        String text = switch (mode) {
            case MANUAL -> "Manual mode: recommendations are informational. You start every optimization yourself;"
                    + " \"Review & optimize\" opens a plan preview where you can check each file.";
            case ASK -> "Ask mode: nothing happens until you approve. Approving a recommendation starts a verified"
                    + " optimization job right away.";
            case AUTONOMOUS -> "Autonomous mode: eligible native-compression recommendations are handled automatically"
                    + " within the policy you confirmed. You can still approve items yourself.";
        };
        banner.getChildren().add(UI.banner(Icons.INFO, text, "info"));
        approveBtn.setText(manual ? "Review & optimize..." : "Approve...");
        approveAllBtn.setVisible(!manual);
        approveAllBtn.setManaged(!manual);
        if (!ctx.workerAvailable()) {
            banner.getChildren().add(UI.banner(Icons.WARNING, "The optimization worker is unavailable: native"
                    + " compression may still work, archive methods and verification will not.", "warn"));
        }
        banner.setSpacing(6);
    }

    private void render() {
        TreeItem<Node> root = new TreeItem<>();
        root.setExpanded(true);
        long total = 0;
        for (Candidate c : loaded) total += c.estSavings();
        if (groupBox.isSelected()) {
            Map<String, List<Candidate>> groups = new LinkedHashMap<>();
            for (Candidate c : loaded) {
                Path p = Path.of(c.path()).getParent();
                groups.computeIfAbsent(p == null ? "" : p.toString(), k -> new ArrayList<>()).add(c);
            }
            for (Map.Entry<String, List<Candidate>> e : groups.entrySet()) {
                TreeItem<Node> g = new TreeItem<>(new Node(e.getKey(), e.getValue()));
                for (Candidate c : e.getValue()) g.getChildren().add(new TreeItem<>(new Node(null, List.of(c))));
                g.setExpanded(groups.size() <= 3);
                root.getChildren().add(g);
            }
        } else {
            for (Candidate c : loaded) root.getChildren().add(new TreeItem<>(new Node(null, List.of(c))));
        }
        table.setRoot(root);
        summary.setText(loaded.isEmpty() ? "" : Fmt.plural(loaded.size(), "recommendation") + " - estimated savings "
                + Fmt.estimate(total) + " (estimates are not guaranteed; real savings are measured after each job)");
        updateButtons();
        showDetails();
    }

    // ------------------------------------------------------------------ selection and actions

    private List<Candidate> selectedCandidates() {
        Map<Long, Candidate> out = new LinkedHashMap<>();
        for (TreeItem<Node> ti : table.getSelectionModel().getSelectedItems()) {
            if (ti == null || ti.getValue() == null) continue;
            for (Candidate c : ti.getValue().candidates()) out.put(c.id(), c);
        }
        return new ArrayList<>(out.values());
    }

    private void configureActions() {
        approveBtn.setOnAction(e -> approve(selectedCandidates()));
        approveAllBtn.setOnAction(e -> approve(loaded));
        rejectBtn.setOnAction(e -> setStatus(CandidateStatus.REJECTED, null, "Rejected"));
        dismissBtn.setOnAction(e -> setStatus(CandidateStatus.DISMISSED, null, "Dismissed"));
        postponeBtn.setGraphic(Icons.CLOCK.node(16));
        for (int days : new int[]{1, 7, 30}) {
            MenuItem mi = new MenuItem(days == 1 ? "1 day" : days + " days");
            mi.setOnAction(e -> setStatus(CandidateStatus.POSTPONED, Duration.ofDays(days),
                    "Postponed for " + days + (days == 1 ? " day" : " days")));
            postponeBtn.getItems().add(mi);
        }
        excludeBtn.setOnAction(e -> excludeSelected());
        pinBtn.setOnAction(e -> pinSelected());
    }

    private void updateButtons() {
        boolean any = !selectedCandidates().isEmpty();
        boolean ro = ctx.engine().readOnly();
        approveBtn.setDisable(!any || ro);
        UI.tooltip(approveBtn, ro ? "Read-only mode: files are never changed" : any ? null : "Select recommendations");
        approveAllBtn.setDisable(loaded.isEmpty() || ro);
        for (Button b : List.of(rejectBtn, dismissBtn, excludeBtn, pinBtn)) b.setDisable(!any);
        postponeBtn.setDisable(!any);
    }

    private void approve(List<Candidate> list) {
        if (list.isEmpty()) return;
        boolean manual = ctx.engine().settings().mode() == AuthorizationMode.MANUAL;
        if (manual) {
            // informational mode: the user initiates through the plan preview
            List<Long> ids = list.stream().map(Candidate::itemId).toList();
            PlanPreviewDialog.show(ctx, owner(), ids, list.get(0).method());
            return;
        }
        long est = list.stream().mapToLong(Candidate::estSavings).sum();
        Set<String> methods = new LinkedHashSet<>();
        boolean replace = false;
        for (Candidate c : list) {
            methods.add(c.method().label());
            replace |= c.method().replacesOriginal();
        }
        String text = Fmt.plural(list.size(), "file") + " will be optimized using: " + String.join("; ", methods)
                + ".\nEstimated savings " + Fmt.estimate(est) + " (estimate)."
                + "\nEach file is re-checked just before it is changed, compression is verified, and everything can be"
                + " restored.";
        CheckBox cb = new CheckBox("I understand the files will be replaced by archives and can only be opened"
                + " again after restoring them.");
        cb.setWrapText(true);
        boolean ok = Dialogs.confirm(owner(), "Approve recommendations", "Approve " + Fmt.plural(list.size(),
                "recommendation") + "?", text, "Approve and start", replace ? cb : null);
        if (!ok) return;
        if (replace && !cb.isSelected()) {
            Dialogs.error(owner(), "Confirmation required", "Archive replacement needs the explicit confirmation."
                    + " Nothing was changed.");
            return;
        }
        boolean confirmed = replace && cb.isSelected();
        Background.supply(() -> ctx.engine().approveRecommendations(list, confirmed)).whenComplete((jobs, err) -> {
            if (err != null) {
                Dialogs.error(owner(), "Cannot approve", Background.message(err));
            } else if (jobs.isEmpty()) {
                toast("Nothing could be started: the selected files are no longer eligible.");
            } else {
                toast("Started " + Fmt.plural(jobs.size(), "job") + " - see Jobs");
                ctx.nav().show(io.github.madaai.idlestow.app.ui.ViewId.JOBS);
            }
            refresh();
        });
    }

    private void setStatus(CandidateStatus status, Duration postpone, String done) {
        List<Candidate> list = selectedCandidates();
        if (list.isEmpty()) return;
        Background.run(() -> ctx.engine().setRecommendationStatus(list, status, postpone)).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot update", Background.message(err));
            else toast(done + ": " + Fmt.plural(list.size(), "recommendation"));
        });
    }

    private void excludeSelected() {
        List<Candidate> list = selectedCandidates();
        if (list.isEmpty()) return;
        if (!Dialogs.confirm(owner(), "Exclude", "Exclude " + Fmt.plural(list.size(), "file") + "?",
                "Excluded files are never recommended or changed. Exclusions can be removed in Settings >"
                        + " Monitoring.", "Exclude")) return;
        Background.run(() -> {
            for (Candidate c : list) ctx.engine().rules().addExclusion("PATH", c.path());
            ctx.engine().rulesChanged();
        }).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot exclude", Background.message(err));
            else toast("Excluded " + Fmt.plural(list.size(), "file"));
        });
    }

    private void pinSelected() {
        List<Candidate> list = selectedCandidates();
        if (list.isEmpty()) return;
        Background.run(() -> {
            for (Candidate c : list) ctx.engine().rules().pin(Path.of(c.path()));
            ctx.engine().rulesChanged();
        }).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot pin", Background.message(err));
            else toast("Pinned " + Fmt.plural(list.size(), "file") + " - they will never be changed");
        });
    }

    // ------------------------------------------------------------------ details

    private void showDetails() {
        details.getChildren().clear();
        List<Candidate> sel = selectedCandidates();
        if (sel.size() != 1) {
            details.getChildren().add(UI.muted(sel.isEmpty()
                    ? "Select a recommendation to see why it was made, the evidence and warnings."
                    : Fmt.plural(sel.size(), "recommendation") + " selected - estimated savings "
                    + Fmt.estimate(sel.stream().mapToLong(Candidate::estSavings).sum())));
            return;
        }
        Candidate c = sel.get(0);
        details.getChildren().addAll(UI.label(c.name(), "h3"), UI.wrapped(c.path(), "muted", "small"),
                UI.wrapped("Why: " + c.reason(), "detail-value"),
                UI.wrapped("Activity: " + c.classification() + ", confidence " + Fmt.title(c.confidence()) + ", "
                        + Fmt.idle(c.idleDays()), "detail-value"),
                UI.wrapped("Method: " + c.method().label() + "   Estimated savings: " + Fmt.estimate(c.estSavings())
                        + " (" + c.estBasis() + ")", "detail-value"),
                UI.wrapped("Policy: " + c.policyRef() + "   Auto-eligible: " + (c.autoEligible() ? "yes" : "no"),
                        "detail-value"));
        if (c.warnings() != null && !c.warnings().isBlank()) {
            details.getChildren().add(UI.banner(Icons.WARNING, c.warnings(), "warn"));
        }
    }

    @SuppressWarnings("unused")
    private boolean rootsFilled() {
        return rootsFilled;
    }
}
