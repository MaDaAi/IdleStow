package io.github.madaai.idlestow.app.ui.views;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.Actions;
import io.github.madaai.idlestow.app.ui.Dialogs;
import io.github.madaai.idlestow.app.ui.Icons;
import io.github.madaai.idlestow.app.ui.Theme;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.Coalescer;
import io.github.madaai.idlestow.app.util.Fmt;
import io.github.madaai.idlestow.app.util.LogLevels;
import io.github.madaai.idlestow.core.db.RulesRepository;
import io.github.madaai.idlestow.core.engine.AppDirectories;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.model.Enums.AuthorizationMode;
import io.github.madaai.idlestow.core.model.Enums.CloseBehavior;
import io.github.madaai.idlestow.core.model.Enums.CompressionProfile;
import io.github.madaai.idlestow.core.model.Enums.Confidence;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.PerformanceMode;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.platform.VolumeInfo;
import io.github.madaai.idlestow.core.settings.Settings;
import io.github.madaai.idlestow.core.util.Sizes;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

/** All settings; every control persists through the engine's validated settings store and reflects the result. */
public final class SettingsView extends BaseView {
    private final StackPane pageHost = new StackPane();
    private final Map<String, Node> pages = new LinkedHashMap<>();
    private final ToggleGroup navGroup = new ToggleGroup();
    private final List<Runnable> syncers = new ArrayList<>();
    private final Coalescer reanalyze = new Coalescer(1500, Platform::runLater, () -> ctx.engine().reanalyze());
    private boolean syncing;

    // dynamic state
    private final TableView<MonitoredRoot> rootTable = new TableView<>();
    private final VBox rootInfo = new VBox(6);
    private final ListView<RulesRepository.Exclusion> exclusionList = new ListView<>();
    private final ListView<RulesRepository.Pin> pinList = new ListView<>();
    private final Label pauseState = UI.label("", "muted");
    private final Label overhead = UI.label("", "muted");
    private final VBox advancedBox = new VBox(8);
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final Map<AuthorizationMode, RadioButton> modeRadios = new LinkedHashMap<>();

    public SettingsView(AppContext ctx) {
        super(ctx);
        VBox nav = new VBox(4);
        nav.setPadding(new Insets(14, 10, 14, 14));
        nav.setPrefWidth(190);
        nav.setMinWidth(190);
        addPage(nav, "General", this::general);
        addPage(nav, "Monitoring", this::monitoring);
        addPage(nav, "Optimization", this::optimization);
        addPage(nav, "Automation", this::automation);
        addPage(nav, "Performance", this::performance);
        addPage(nav, "Recovery", this::recovery);
        addPage(nav, "Privacy", this::privacy);
        addPage(nav, "Advanced", this::advanced);
        javafx.scene.layout.HBox root = new HBox(nav, pageHost);
        HBox.setHgrow(pageHost, Priority.ALWAYS);
        setContent(root);
        ((ToggleButton) nav.getChildren().get(0)).setSelected(true);
        showPage("General");
    }

    @Override
    public String title() {
        return "Settings";
    }

    @Override
    protected Set<IdleStowEngine.Event> events() {
        return Set.of(IdleStowEngine.Event.ROOTS, IdleStowEngine.Event.SETTINGS, IdleStowEngine.Event.STATUS,
                IdleStowEngine.Event.RECOVERY);
    }

    private void addPage(VBox nav, String name, java.util.function.Supplier<Node> builder) {
        ToggleButton b = new ToggleButton(name);
        b.setToggleGroup(navGroup);
        b.getStyleClass().add("nav-button");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> {
            b.setSelected(true);
            showPage(name);
        });
        nav.getChildren().add(b);
        Node content = builder.get();
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pages.put(name, sp);
    }

    private void showPage(String name) {
        pageHost.getChildren().setAll(pages.get(name));
    }

    // ------------------------------------------------------------------ loading

    @Override
    protected CompletableFuture<Void> load() {
        return Background.supply(() -> {
            IdleStowEngine e = ctx.engine();
            List<MonitoredRoot> roots = e.roots();
            Map<String, VolumeInfo> vols = new LinkedHashMap<>();
            for (MonitoredRoot r : roots) e.volume(r.volumeId()).ifPresent(v -> vols.put(r.volumeId(), v));
            String formats;
            try {
                formats = e.worker().supportedFormats().toString();
            } catch (Exception ex) {
                formats = "unavailable (" + Background.message(ex) + ")";
            }
            String dbCheck;
            try {
                dbCheck = e.database().quickCheck();
            } catch (Exception ex) {
                dbCheck = "check failed: " + Background.message(ex);
            }
            return new Object[]{roots, vols, e.rules().exclusions(), e.rules().pins(), formats, dbCheck,
                    e.database().currentVersion(), e.recoveryRecords().retainedBytes()};
        }).thenAccept(res -> {
            @SuppressWarnings("unchecked")
            List<MonitoredRoot> roots = (List<MonitoredRoot>) res[0];
            @SuppressWarnings("unchecked")
            Map<String, VolumeInfo> vols = (Map<String, VolumeInfo>) res[1];
            @SuppressWarnings("unchecked")
            List<RulesRepository.Exclusion> ex = (List<RulesRepository.Exclusion>) res[2];
            @SuppressWarnings("unchecked")
            List<RulesRepository.Pin> pins = (List<RulesRepository.Pin>) res[3];
            MonitoredRoot sel = rootTable.getSelectionModel().getSelectedItem();
            rootTable.setItems(FXCollections.observableArrayList(roots));
            if (sel != null) {
                for (int i = 0; i < roots.size(); i++) {
                    if (roots.get(i).id() == sel.id()) rootTable.getSelectionModel().select(i);
                }
            }
            volumes = vols;
            exclusionList.setItems(FXCollections.observableArrayList(ex));
            pinList.setItems(FXCollections.observableArrayList(pins));
            overhead.setText("Retained originals currently use " + Fmt.size((Long) res[7])
                    + " of extra space (measured).");
            renderAdvanced((String) res[4], (String) res[5], (Integer) res[6], roots, vols);
            renderRootInfo();
            syncAll();
        });
    }

    private Map<String, VolumeInfo> volumes = Map.of();

    private void syncAll() {
        syncing = true;
        try {
            for (Runnable r : syncers) r.run();
            IdleStowEngine e = ctx.engine();
            RadioButton rb = modeRadios.get(e.settings().mode());
            if (rb != null) rb.setSelected(true);
            pauseState.setText(e.paused() ? "Optimization and background work are paused." : "Not paused.");
        } finally {
            syncing = false;
        }
    }

    // ------------------------------------------------------------------ persistence helpers

    /** Persists one raw setting through the validated store, then re-syncs every control from the stored value. */
    private void put(String key, String value) {
        if (syncing) return;
        Background.run(() -> ctx.engine().settingsStore().update(key, value)).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot save the setting", Background.message(err));
            syncAll();
            if (key.startsWith("policy.") || key.startsWith("opt.")) reanalyze.request();
        });
    }

    private CheckBox toggle(String label, String key, Function<Settings, Boolean> get) {
        CheckBox cb = new CheckBox(label);
        cb.setWrapText(true);
        cb.setOnAction(e -> {
            if (!syncing) put(key, Boolean.toString(cb.isSelected()));
        });
        syncers.add(() -> cb.setSelected(get.apply(ctx.engine().settings())));
        return cb;
    }

    private <E extends Enum<E>> ComboBox<E> choice(String key, Class<E> type, Function<Settings, E> get,
                                                   Function<E, String> label) {
        ComboBox<E> box = new ComboBox<>(FXCollections.observableArrayList(type.getEnumConstants()));
        javafx.util.StringConverter<E> conv = new javafx.util.StringConverter<>() {
            @Override
            public String toString(E e) {
                return e == null ? "" : label.apply(e);
            }

            @Override
            public E fromString(String s) {
                return null;
            }
        };
        box.setConverter(conv);
        box.setOnAction(e -> {
            if (!syncing && box.getValue() != null) put(key, box.getValue().name());
        });
        syncers.add(() -> box.setValue(get.apply(ctx.engine().settings())));
        return box;
    }

    /** Integer spinner whose displayed value is {@code get(settings)}; writes {@code value * factor}. */
    private Spinner<Integer> number(String key, int min, int max, long factor, Function<Settings, Long> get) {
        Spinner<Integer> sp = new Spinner<>();
        sp.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, min));
        sp.setEditable(true);
        sp.setPrefWidth(130);
        sp.valueProperty().addListener((o, a, b) -> {
            if (!syncing && b != null) put(key, Long.toString(b * factor));
        });
        sp.focusedProperty().addListener((o, a, focused) -> {
            if (!focused) {
                try {
                    sp.getValueFactory().setValue(Integer.parseInt(sp.getEditor().getText().trim()));
                } catch (NumberFormatException e) {
                    sp.getEditor().setText(String.valueOf(sp.getValue()));
                }
            }
        });
        syncers.add(() -> {
            long v = get.apply(ctx.engine().settings()) / factor;
            int clamped = (int) Math.max(min, Math.min(max, v));
            sp.getValueFactory().setValue(clamped);
            sp.getEditor().setText(String.valueOf(clamped));
        });
        return sp;
    }

    private static VBox page(String title, String intro, Node... rows) {
        VBox v = new VBox(14);
        v.setPadding(new Insets(20, 26, 26, 20));
        v.setMaxWidth(900);
        v.getChildren().add(UI.label(title, "h1"));
        if (intro != null) v.getChildren().add(UI.wrapped(intro, "muted"));
        v.getChildren().addAll(rows);
        return v;
    }

    private static VBox row(String label, Node control, String hint) {
        Label l = UI.label(label, "detail-value");
        l.setMinWidth(250);
        l.setPrefWidth(250);
        l.setWrapText(true);
        HBox h = new HBox(14, l, control);
        h.setAlignment(Pos.CENTER_LEFT);
        VBox v = new VBox(2, h);
        if (hint != null) {
            Label hl = UI.wrapped(hint, "form-hint");
            hl.setPadding(new Insets(0, 0, 0, 264));
            v.getChildren().add(hl);
        }
        return v;
    }

    private static VBox box(String title, Node... rows) {
        VBox v = new VBox(10);
        v.getStyleClass().add("card");
        if (title != null) v.getChildren().add(UI.label(title, "h3"));
        v.getChildren().addAll(rows);
        return v;
    }

    // ------------------------------------------------------------------ General

    private Node general() {
        ComboBox<Theme.Mode> theme = new ComboBox<>(FXCollections.observableArrayList(Theme.Mode.values()));
        theme.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Theme.Mode m) {
                return m == null ? "" : m == Theme.Mode.DARK ? "Dark" : "Light";
            }

            @Override
            public Theme.Mode fromString(String s) {
                return null;
            }
        });
        theme.setOnAction(e -> {
            if (!syncing && theme.getValue() != null) {
                Theme.set(theme.getValue());
                put("ui.theme", theme.getValue().setting());
            }
        });
        syncers.add(() -> theme.setValue(Theme.Mode.of(ctx.engine().settings().theme())));
        ComboBox<CloseBehavior> close = choice("ui.closeBehavior", CloseBehavior.class,
                s -> s.closeBehavior(), c -> c == CloseBehavior.EXIT ? "Exit IdleStow" : "Keep running in the tray");
        boolean tray = ctx.lifecycle() != null && ctx.lifecycle().trayAvailable();
        String closeHint = "Exit: closing the window quits IdleStow, background work stops. Tray: the window hides and"
                + " IdleStow keeps indexing and optimizing in the background; use the tray icon to open or exit."
                + (tray ? "" : " The system tray is not available here, so closing the window always exits.");
        close.setDisable(!tray);
        return page("General", "Appearance and window behaviour.",
                box(null, row("Theme", theme, "Applies immediately."),
                        row("When the window is closed", close, closeHint),
                        row("Notifications", toggle("Notify when jobs finish or fail", "ui.notifications",
                                Settings::notifications), "Shown through the system tray.")));
    }

    // ------------------------------------------------------------------ Monitoring

    private Node monitoring() {
        rootTable.setPlaceholder(UI.muted("No folders authorized. IdleStow only looks at folders you add."));
        rootTable.getColumns().add(UI.col("Folder", 360, r -> r.path().toString()));
        rootTable.getColumns().add(UI.col("Status", 90, r -> r.active() ? "Active" : "Revoked"));
        rootTable.getColumns().add(UI.col("Last scan", 160, r -> Fmt.iso(r.lastScanUtc())));
        rootTable.getColumns().add(UI.col("Autonomy", 80, r -> r.autonomyEnabled() ? "Allowed" : "No"));
        rootTable.getColumns().add(UI.col("Archival", 80, r -> r.archival() ? "Allowed" : "No"));
        rootTable.setPrefHeight(200);
        rootTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> renderRootInfo());

        Button add = UI.button("Add folder...", Icons.PLUS, () -> Actions.addFolder(ctx, owner(), true,
                r -> refresh()), "primary");
        Button rescan = UI.button("Rescan", Icons.REFRESH, () -> withRoot(r -> {
            ctx.engine().scan(r.id());
            toast("Rescanning " + r.displayName());
        }));
        Button revoke = UI.button("Revoke / re-authorize", Icons.LOCK, () -> withRoot(this::toggleRevoke));
        Button remove = UI.button("Remove...", Icons.X, () -> withRoot(this::removeRoot), "danger");
        FlowPane bar = new FlowPane(8, 6, add, rescan, revoke, remove);

        ComboBox<Integer> interval = new ComboBox<>(FXCollections.observableArrayList(1, 2, 6, 12, 24, 72, 168));
        interval.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Integer h) {
                return h == null ? "" : h == 1 ? "every hour" : h < 48 ? "every " + h + " hours" : "every " + (h / 24)
                        + " days";
            }

            @Override
            public Integer fromString(String s) {
                return null;
            }
        });
        interval.setOnAction(e -> {
            if (!syncing && interval.getValue() != null) put("bg.scanIntervalHours", interval.getValue().toString());
        });
        syncers.add(() -> {
            int h = ctx.engine().settings().scanIntervalHours();
            if (!interval.getItems().contains(h)) interval.getItems().add(h);
            interval.setValue(h);
        });

        TextField exValue = new TextField();
        exValue.setPromptText("value, e.g. C:\\Projects\\keep or *.psd");
        exValue.setPrefWidth(300);
        ComboBox<String> exKind = new ComboBox<>(FXCollections.observableArrayList(RulesRepository.EXCLUSION_KINDS));
        exKind.setValue("PATH");
        Button exAdd = UI.button("Add exclusion", Icons.PLUS, () -> {
            String kind = exKind.getValue();
            String val = exValue.getText();
            Background.run(() -> {
                ctx.engine().rules().addExclusion(kind, val);
                ctx.engine().rulesChanged();
            }).whenComplete((v, err) -> {
                if (err != null) Dialogs.error(owner(), "Cannot add the exclusion", Background.message(err));
                else exValue.clear();
                refresh();
            });
        });
        Button exRemove = UI.button("Remove selected", Icons.X, () -> {
            var sel = exclusionList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Background.run(() -> {
                ctx.engine().rules().removeExclusion(sel.id());
                ctx.engine().rulesChanged();
            }).whenComplete((v, err) -> {
                if (err != null) Dialogs.error(owner(), "Cannot remove", Background.message(err));
                refresh();
            });
        });
        exclusionList.setPrefHeight(150);
        exclusionList.setPlaceholder(UI.muted("No exclusions. Excluded items are never recommended or changed."));
        exclusionList.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(RulesRepository.Exclusion x, boolean empty) {
                super.updateItem(x, empty);
                setText(empty || x == null ? null : Fmt.title(x.kind()) + ":  " + x.value());
            }
        });
        pinList.setPrefHeight(130);
        pinList.setPlaceholder(UI.muted("No pinned items. Pin files or folders in Explorer to protect them."));
        pinList.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(RulesRepository.Pin p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : p.path().toString());
            }
        });
        Button unpin = UI.button("Unpin selected", Icons.PIN, () -> {
            var sel = pinList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Background.run(() -> {
                ctx.engine().rules().unpin(sel.path());
                ctx.engine().rulesChanged();
            }).whenComplete((v, err) -> {
                if (err != null) Dialogs.error(owner(), "Cannot unpin", Background.message(err));
                refresh();
            });
        });
        return page("Monitoring", "IdleStow never looks outside the folders listed here. Revoking a folder stops all"
                        + " work on it immediately; removing it deletes only IdleStow's index of it, never your files.",
                box("Authorized folders", rootTable, bar, rootInfo),
                box("Scanning", row("Rescan", interval, "How often background scanning runs (needs Background on in"
                                + " Automation)."),
                        toggle("Watch folders for changes", "bg.watch", Settings::watchEnabled)),
                box("Exclusions", exclusionList, UI.hbox(8, exKind, exValue, exAdd, exRemove)),
                box("Pinned items", pinList, unpin));
    }

    private void withRoot(java.util.function.Consumer<MonitoredRoot> action) {
        MonitoredRoot r = rootTable.getSelectionModel().getSelectedItem();
        if (r == null) toast("Select a folder first");
        else action.accept(r);
    }

    private void toggleRevoke(MonitoredRoot r) {
        boolean revoke = r.active();
        if (revoke && !Dialogs.confirm(owner(), "Revoke authorization", "Revoke " + r.displayName() + "?",
                "IdleStow stops all work on this folder immediately. Existing compressed files stay as they are and"
                        + " remain restorable.", "Revoke")) return;
        Background.run(() -> {
            if (revoke) ctx.engine().revokeRoot(r.id());
            else ctx.engine().reauthorizeRoot(r.id());
        }).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot change authorization", Background.message(err));
            refresh();
        });
    }

    private void removeRoot(MonitoredRoot r) {
        if (!Dialogs.confirm(owner(), "Remove folder", "Remove " + r.displayName() + " from IdleStow?",
                "Only IdleStow's index of this folder is removed. Your files, archives and recovery records stay"
                        + " exactly where they are.", "Remove")) return;
        Background.run(() -> ctx.engine().removeRoot(r.id())).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot remove", Background.message(err));
            refresh();
        });
    }

    private void renderRootInfo() {
        rootInfo.getChildren().clear();
        MonitoredRoot r = rootTable.getSelectionModel().getSelectedItem();
        if (r == null) {
            rootInfo.getChildren().add(UI.muted("Select a folder to change its permissions and see what its"
                    + " filesystem supports."));
            return;
        }
        CheckBox auto = new CheckBox("Allow autonomous optimization in this folder");
        auto.setSelected(r.autonomyEnabled());
        auto.setOnAction(e -> Background.run(() -> ctx.engine().setRootAutonomy(r.id(), auto.isSelected()))
                .whenComplete((v, err) -> {
                    if (err != null) Dialogs.error(owner(), "Cannot change", Background.message(err));
                    refresh();
                }));
        CheckBox arch = new CheckBox("Allow archives (ZIP / tar.zst) in this folder");
        arch.setSelected(r.archival());
        arch.setOnAction(e -> Background.run(() -> ctx.engine().setRootArchival(r.id(), arch.isSelected()))
                .whenComplete((v, err) -> {
                    if (err != null) Dialogs.error(owner(), "Cannot change", Background.message(err));
                    refresh();
                }));
        rootInfo.getChildren().addAll(UI.divider(), auto, arch);
        VolumeInfo v = volumes.get(r.volumeId());
        if (v == null) {
            rootInfo.getChildren().add(UI.muted("Filesystem details are not available yet."));
        } else {
            rootInfo.getChildren().addAll(
                    UI.wrapped("Filesystem: " + v.fileSystemType() + " on " + v.rootPath()
                            + (v.readOnly() ? " (read-only)" : "") + (v.remote() ? " (network)" : ""), "detail-value"),
                    UI.wrapped("Native compression: " + (v.nativeCompression() ? "supported" : "not supported"
                            + (v.nativeCompressionNote() == null || v.nativeCompressionNote().isBlank() ? ""
                            : " - " + v.nativeCompressionNote())), "detail-value"),
                    UI.wrapped("Last-access tracking: " + Fmt.title(v.accessTracking().name())
                            + (v.accessTrackingNote() == null || v.accessTrackingNote().isBlank() ? ""
                            : " - " + v.accessTrackingNote()), "detail-value"));
        }
    }

    // ------------------------------------------------------------------ Optimization

    private Node optimization() {
        int[] presets = {7, 14, 30, 60, 90, 180, 365};
        ComboBox<String> threshold = new ComboBox<>();
        for (int p : presets) threshold.getItems().add(p + " days");
        threshold.getItems().add("Custom");
        Spinner<Integer> custom = number("policy.inactivityDays", 1, 3650, 1, s -> (long) s.inactivityDays());
        threshold.setOnAction(e -> {
            if (syncing || threshold.getValue() == null) return;
            String v = threshold.getValue();
            if (!v.equals("Custom")) put("policy.inactivityDays", v.replace(" days", ""));
            custom.setDisable(!v.equals("Custom"));
        });
        syncers.add(() -> {
            int d = ctx.engine().settings().inactivityDays();
            String match = "Custom";
            for (int p : presets) if (p == d) match = p + " days";
            threshold.setValue(match);
            custom.setDisable(!match.equals("Custom"));
        });
        CheckBox replace = new CheckBox("Enable archive replacement (originals are replaced by verified archives)");
        replace.setWrapText(true);
        replace.setOnAction(e -> {
            if (syncing) return;
            if (replace.isSelected() && !Dialogs.confirm(owner(), "Enable archive replacement",
                    "Replace originals with archives?", "When you approve it, a file can be replaced by a verified"
                            + " archive. Other programs cannot open it until you restore it. The original is kept for the"
                            + " retention period, after which the archive is the only copy. IdleStow is not a backup."
                            + " Archive replacement is never done autonomously.", "Enable")) {
                syncAll();
                return;
            }
            put("opt.archiveReplaceEnabled", Boolean.toString(replace.isSelected()));
        });
        syncers.add(() -> replace.setSelected(ctx.engine().settings().archiveReplaceEnabled()));
        return page("Optimization", "What counts as idle and when compression is worth recommending.",
                box("Inactivity", row("Inactivity threshold", UI.hbox(8, threshold, custom),
                                "A file is a candidate only after this long without evidence of use."),
                        row("Observation period (days)", number("policy.observationDays", 0, 365, 1,
                                s -> (long) s.observationDays()), "IdleStow must have watched this long before it"
                                + " trusts a file's inactivity. Last-access times alone are never enough."),
                        row("Minimum confidence to recommend", choice("policy.minConfidenceRecommend", Confidence.class,
                                Settings::minConfidenceRecommend, c -> Fmt.title(c.name())), null),
                        row("Minimum confidence for autonomy", choice("policy.minConfidenceAuto", Confidence.class,
                                Settings::minConfidenceAuto, c -> Fmt.title(c.name())), "Autonomy never uses less than"
                                + " medium.")),
                box("Worth it", row("Minimum savings (MiB)", number("opt.minSavingsBytes", 0, 1_000_000, Sizes.MIB,
                                s -> s.minSavingsBytes()), null),
                        row("Minimum savings (%)", number("opt.minSavingsPercent", 0, 99, 1,
                                s -> (long) s.minSavingsPercent()), null),
                        row("Minimum file size (KiB)", number("opt.minFileSize", 0, 100_000_000, Sizes.KIB,
                                s -> s.minFileSize()), null),
                        row("Maximum file size (MiB, 0 = no limit)", number("opt.maxFileSize", 0, 10_000_000, Sizes.MIB,
                                s -> s.maxFileSize()), null)),
                box("Method", row("Compression profile", choice("opt.profile", CompressionProfile.class,
                                Settings::profile, p -> Fmt.title(p.name())), "Maximum compresses harder and is slower."),
                        row("Preferred method", choice("opt.preferredMethod", Method.class, Settings::preferredMethod,
                                Method::label), "Native transparent compression keeps files openable and is fully"
                                + " reversible."),
                        replace,
                        row("Keep originals for (days)", number("recovery.retentionDays", 1, 3650, 1,
                                s -> (long) s.retentionDays()), "Retention of the original after an archive"
                                + " replacement.")));
    }

    // ------------------------------------------------------------------ Automation

    private Node automation() {
        VBox modes = new VBox(8);
        String[] desc = {"Manual: IdleStow only recommends. You start everything yourself.",
            "Ask: you approve recommendations and IdleStow then runs them. (Recommended)",
            "Autonomous: IdleStow optimizes eligible files by itself within a policy you confirm."};
        int i = 0;
        for (AuthorizationMode m : new AuthorizationMode[]{AuthorizationMode.MANUAL, AuthorizationMode.ASK,
            AuthorizationMode.AUTONOMOUS}) {
            RadioButton rb = new RadioButton(desc[i++]);
            rb.setToggleGroup(modeGroup);
            rb.setWrapText(true);
            rb.setOnAction(e -> chooseMode(m));
            modeRadios.put(m, rb);
            modes.getChildren().add(rb);
        }
        CheckBox login = new CheckBox("Start IdleStow when I sign in (in the background)");
        boolean supported = ctx.engine().platform().startupRegistration().supported();
        login.setDisable(!supported);
        login.setOnAction(e -> {
            if (!syncing) setStartAtLogin(login.isSelected());
        });
        syncers.add(() -> login.setSelected(ctx.engine().settings().startAtLogin()));
        Button p1 = UI.button("Pause 1 hour", Icons.PAUSE, () -> ctx.engine().pauseAll(Duration.ofHours(1)));
        Button p4 = UI.button("Pause 4 hours", Icons.PAUSE, () -> ctx.engine().pauseAll(Duration.ofHours(4)));
        Button pu = UI.button("Pause until I resume", Icons.PAUSE, () -> ctx.engine().pauseAll(null));
        Button resume = UI.button("Resume", Icons.PLAY, () -> ctx.engine().resumeAll());
        return page("Automation", "Who decides when files are optimized, and whether IdleStow works in the"
                        + " background.",
                box("Mode", modes, UI.wrapped("Autonomous mode asks you to confirm its exact policy first and only"
                        + " uses reversible native compression.", "form-hint")),
                box("Background", toggle("Scan and optimize in the background", "bg.enabled",
                                Settings::backgroundEnabled), login,
                        supported ? UI.wrapped("Registers this application with --background for your user only (no"
                                + " administrator rights).", "form-hint")
                                : UI.wrapped("Start at login is not supported on this platform.", "form-hint"),
                        row("Autonomous pass", number("bg.autoIntervalHours", 1, 168, 1, s -> (long) s.autoIntervalHours()),
                                "Hours between autonomous optimization passes."),
                        toggle("Only when I am idle", "bg.idleOnly", Settings::idleOnly),
                        row("Idle for at least (minutes)", number("bg.idleMinutes", 1, 240, 1,
                                s -> (long) s.idleMinutes()), null),
                        toggle("Also work while on battery power", "bg.autoOnBattery", Settings::autoOnBattery),
                        row("Maximum CPU use (%)", number("bg.maxCpuPercent", 5, 100, 1,
                                s -> (long) s.maxCpuPercent()), "Autonomous work waits while the system is busier.")),
                box("Pause", pauseState, new FlowPane(8, 6, p1, p4, pu, resume)));
    }

    private void chooseMode(AuthorizationMode m) {
        if (syncing) return;
        if (m != AuthorizationMode.AUTONOMOUS) {
            Background.run(() -> ctx.engine().setMode(m)).whenComplete((v, err) -> {
                if (err != null) Dialogs.error(owner(), "Cannot change the mode", Background.message(err));
                syncAll();
            });
            return;
        }
        Background.supply(() -> ctx.engine().currentAutonomyPolicy()).whenComplete((policy, err) -> {
            if (err != null) {
                Dialogs.error(owner(), "Cannot read the policy", Background.message(err));
                syncAll();
                return;
            }
            if (confirmAutonomy(owner(), policy)) {
                Background.run(() -> ctx.engine().confirmAutonomy(policy)).whenComplete((v, e2) -> {
                    if (e2 != null) Dialogs.error(owner(), "Autonomy was not enabled", Background.message(e2));
                    else toast("Autonomous mode enabled with the confirmed policy");
                    syncAll();
                });
            } else {
                syncAll();
            }
        });
    }

    /** Shows exactly what autonomy may do; true only on explicit confirmation. Shared with the onboarding wizard. */
    public static boolean confirmAutonomy(javafx.stage.Window owner, IdleStowEngine.AutonomyPolicy p) {
        VBox v = new VBox(6);
        v.setPrefWidth(560);
        v.getChildren().add(UI.wrapped("In autonomous mode IdleStow compresses files without asking each time."
                + " This is exactly what you are allowing:", "detail-value"));
        v.getChildren().add(UI.label("Folders", "section-title"));
        if (p.folders().isEmpty()) {
            v.getChildren().add(UI.banner(Icons.WARNING, "No folder has autonomy enabled. Enable it for at least one"
                    + " folder in Settings > Monitoring first.", "warn"));
        } else {
            for (String f : p.folders()) v.getChildren().add(UI.wrapped("\u2022 " + f, "detail-value"));
        }
        v.getChildren().addAll(
                UI.label("Methods", "section-title"), UI.wrapped(String.join(", ", p.methods().stream()
                        .map(n -> Method.valueOf(n).label()).toList()), "detail-value"),
                UI.label("When", "section-title"),
                UI.wrapped("Files idle for at least " + p.inactivityDays() + " days, minimum confidence "
                        + Fmt.title(p.minConfidence()) + ", watched for at least " + p.observationDays() + " days.",
                        "detail-value"),
                UI.label("Resource limits", "section-title"),
                UI.wrapped("CPU at most " + p.maxCpuPercent() + "%, " + (p.idleOnly() ? "only while you are idle, "
                        : "") + (p.onBattery() ? "also on battery, " : "not on battery, ") + "at most " + p.maxBatch()
                        + " files per pass.", "detail-value"),
                UI.label("Never touched", "section-title"),
                UI.wrapped("System and program files, cloud-synchronized folders, pinned and excluded items, files in"
                        + " use, and anything outside the folders above.", "detail-value"),
                UI.label("Recovery", "section-title"), UI.wrapped(p.recoveryLimitations(), "detail-value"),
                UI.label("Stopping it", "section-title"),
                UI.wrapped("Pause from the toolbar or tray at any time, switch the mode back in Settings > Automation,"
                        + " or revoke a folder. Changing any of the values above requires your confirmation again.",
                        "detail-value"));
        return confirmDialog(owner, "Enable autonomous mode?", v, p.folders().isEmpty());
    }

    private static boolean confirmDialog(javafx.stage.Window owner, String title, Node content, boolean blocked) {
        javafx.scene.control.Dialog<Boolean> d = new javafx.scene.control.Dialog<>();
        if (owner != null) d.initOwner(owner);
        d.setTitle(title);
        d.setHeaderText(title);
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setPrefViewportHeight(430);
        d.getDialogPane().setContent(sp);
        javafx.scene.control.ButtonType ok = new javafx.scene.control.ButtonType("Enable autonomous mode",
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType cancel = new javafx.scene.control.ButtonType("Cancel",
                javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        d.getDialogPane().getButtonTypes().setAll(ok, cancel);
        d.getDialogPane().lookupButton(ok).setDisable(blocked);
        ((Button) d.getDialogPane().lookupButton(ok)).setDefaultButton(false);
        ((Button) d.getDialogPane().lookupButton(cancel)).setDefaultButton(true);
        d.setResultConverter(bt -> bt == ok);
        Theme.style(d);
        return d.showAndWait().orElse(false);
    }

    private void setStartAtLogin(boolean on) {
        Background.run(() -> {
            var reg = ctx.engine().platform().startupRegistration();
            if (on) reg.register(Actions.startupCommand(ctx));
            else reg.unregister();
            ctx.engine().settingsStore().update("bg.startAtLogin", Boolean.toString(on));
        }).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner(), "Start at login", Background.message(err));
            syncAll();
        });
    }

    // ------------------------------------------------------------------ Performance

    private Node performance() {
        return page("Performance", "How hard IdleStow works.",
                box(null, row("Mode", choice("perf.mode", PerformanceMode.class, Settings::performanceMode,
                                p -> Fmt.title(p.name())), "Silent keeps analysis light; Performance estimates more files"
                                + " per scan."),
                        row("Parallel jobs", number("perf.jobConcurrency", 1, 4, 1, s -> (long) s.jobConcurrency()),
                                "Takes effect after IdleStow restarts."),
                        row("Compressibility sample (KiB)", number("perf.maxSampleBytes", 64, 65536, Sizes.KIB,
                                s -> s.maxSampleBytes()), "How much of a file is read to estimate savings."),
                        row("Keep free space (MiB)", number("perf.minFreeReserveBytes", 256, 100_000_000, Sizes.MIB,
                                s -> s.minFreeReserveBytes()), "IdleStow never starts work that would leave less"
                                + " free space than this."),
                        row("Temporary space limit (MiB)", number("perf.maxTempBytes", 64, 100_000_000, Sizes.MIB,
                                s -> s.maxTempBytes()), null),
                        row("Files per batch", number("perf.maxBatchSize", 1, 100_000, 1, s -> (long) s.maxBatchSize()),
                                null)));
    }

    // ------------------------------------------------------------------ Recovery

    private Node recovery() {
        return page("Recovery", "Everything IdleStow does can be reversed.",
                box(null, row("Keep originals for (days)", number("recovery.retentionDays", 1, 3650, 1,
                                s -> (long) s.retentionDays()), "After an archive replacement the original is kept this"
                                + " long. Afterwards the archive is the only copy."),
                        overhead,
                        UI.wrapped("Every archive is fully decompressed and its SHA-256 compared with the original"
                                + " before the original is touched, and re-verified before retained originals are"
                                + " removed. Native compression is verified by reading back the file state. IdleStow is"
                                + " not a backup: keep your own backups.", "muted")));
    }

    // ------------------------------------------------------------------ Privacy

    private Node privacy() {
        Path logs = AppDirectories.logDir(ctx.dataDir());
        Button open = UI.button("Open data folder", Icons.FOLDER_OPEN, () -> Actions.openFolder(owner(), ctx.dataDir()));
        Button export = UI.button("Export my data...", Icons.DOWNLOAD, () -> {
            var t = Dialogs.saveFile(owner(), "Export IdleStow data", "idlestow-export.json", "JSON (*.json)", "*.json");
            t.ifPresent(p -> Background.run(() -> ctx.engine().exportData(p)).whenComplete((v, err) -> {
                if (err != null) Dialogs.error(owner(), "Export failed", Background.message(err));
                else toast("Exported to " + p);
            }));
        });
        Button clear = UI.button("Clear the file index...", Icons.X, this::clearIndex, "danger");
        Button clearLogs = UI.button("Clear diagnostic logs...", Icons.X, () -> clearLogs(logs));
        return page("Privacy", null,
                box("Everything stays on this computer", UI.wrapped("IdleStow has no accounts, no cloud service and no"
                        + " telemetry. It never uploads file names, contents or statistics. The index, history and"
                        + " settings live in the data folder below. Diagnostic logs may contain file paths but never"
                        + " file contents.", "detail-value"),
                        UI.wrapped("Data folder: " + ctx.dataDir(), "mono"), UI.wrapped("Logs: " + logs, "mono"),
                        new FlowPane(8, 6, open, export)),
                box("Clear local data", UI.wrapped("Clearing the index removes IdleStow's knowledge of your files"
                        + " (rebuilt by the next scan). Settings, authorized folders, history and recovery records stay,"
                        + " so nothing needed to restore files is lost. Your files are never touched.", "muted"),
                        new FlowPane(8, 6, clear, clearLogs)));
    }

    private void clearIndex() {
        if (!Dialogs.confirm(owner(), "Clear the file index", "Clear the index?",
                "The index and recommendations are removed and rebuilt by the next scan. Your files, settings,"
                        + " history and recovery records are not affected.", "Clear index")) return;
        Background.run(() -> ctx.engine().clearIndex()).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot clear the index", Background.message(err));
            else toast("Index cleared. Press F5 to scan again.");
        });
    }

    private void clearLogs(Path dir) {
        if (!Dialogs.confirm(owner(), "Clear logs", "Delete diagnostic logs?",
                "Old log files in " + dir + " are deleted (the active log stays). This does not affect your files or"
                        + " IdleStow's data.", "Delete logs")) return;
        Background.supply(() -> {
            int n = 0;
            if (!Files.isDirectory(dir)) return 0;
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.toList()) {
                    String name = p.getFileName().toString();
                    if (Files.isRegularFile(p) && name.startsWith("idlestow") && name.endsWith(".log")
                            && !name.equals("idlestow.log")) {
                        try {
                            Files.delete(p);
                            n++;
                        } catch (IOException ignored) {
                            // in use
                        }
                    }
                }
            }
            return n;
        }).whenComplete((n, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot clear logs", Background.message(err));
            else toast("Deleted " + Fmt.plural(n, "log file"));
        });
    }

    // ------------------------------------------------------------------ Advanced

    private Node advanced() {
        CheckBox verbose = new CheckBox("Verbose logging");
        verbose.setOnAction(e -> {
            if (syncing) return;
            LogLevels.applyVerbose(verbose.isSelected());
            put("adv.verboseLogging", Boolean.toString(verbose.isSelected()));
        });
        syncers.add(() -> verbose.setSelected(ctx.engine().settings().verboseLogging()));
        TextArea licenses = new TextArea(licenseText());
        licenses.setEditable(false);
        licenses.setPrefRowCount(10);
        licenses.getStyleClass().add("mono");
        return page("Advanced", null, box("Diagnostics", advancedBox, verbose),
                box("About", UI.label("IdleStow " + AppContext.version(), "h3"),
                        UI.muted("Idle files. Reclaimed space. Apache License 2.0."), UI.label("Third-party licenses",
                                "section-title"), licenses));
    }

    private void renderAdvanced(String formats, String dbCheck, int schema, List<MonitoredRoot> roots,
                                Map<String, VolumeInfo> vols) {
        advancedBox.getChildren().clear();
        advancedBox.getChildren().add(UI.wrapped("Worker: " + ctx.engine().worker().status() + "   Archive formats: "
                + formats, "detail-value"));
        advancedBox.getChildren().add(UI.wrapped(ctx.workerNote(), "muted", "small"));
        advancedBox.getChildren().add(UI.wrapped("Database: " + (dbCheck.equals("ok") ? "healthy" : dbCheck)
                + "   Schema version " + schema, dbCheck.equals("ok") ? "detail-value" : "error-text"));
        var p = ctx.engine().platform();
        advancedBox.getChildren().add(UI.wrapped("Platform adapter: " + p.name() + "   Native compression implemented: "
                + p.nativeCompression().implemented(), "detail-value"));
        for (MonitoredRoot r : roots) {
            VolumeInfo v = vols.get(r.volumeId());
            if (v != null) {
                advancedBox.getChildren().add(UI.wrapped(r.displayName() + ": " + v.fileSystemType() + ", cluster "
                        + (v.clusterSize() > 0 ? Fmt.size(v.clusterSize()) : "unknown") + ", native compression "
                        + (v.nativeCompression() ? "yes" : "no") + ", access tracking "
                        + Fmt.title(v.accessTracking().name()), "detail-value"));
            }
        }
    }

    private static String licenseText() {
        try (var in = SettingsView.class.getResourceAsStream("/THIRD-PARTY-NOTICES")) {
            if (in != null) return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // fall through to the built-in list
        }
        return """
                OpenJFX (javafx-controls, -graphics, -base) 21 - GPL v2 with Classpath Exception
                SQLite JDBC (org.xerial) - Apache License 2.0 (SQLite itself is public domain)
                Jackson (jackson-databind, -core, -annotations) - Apache License 2.0
                SLF4J API - MIT License
                Logback (classic, core) - EPL 1.0 / LGPL 2.1
                JNA (jna, jna-platform) - Apache License 2.0 / LGPL 2.1
                Python worker - uses the Python standard library (PSF License); see worker/ for bundled packages.
                """;
    }
}
