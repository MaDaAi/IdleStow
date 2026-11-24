package io.github.madaai.idlestow.app.ui.views;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.Actions;
import io.github.madaai.idlestow.app.ui.Dialogs;
import io.github.madaai.idlestow.app.ui.Icons;
import io.github.madaai.idlestow.app.ui.ViewId;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.Fmt;
import io.github.madaai.idlestow.core.db.Queries;
import io.github.madaai.idlestow.core.db.RecoveryRepository;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.model.ActivityAssessment;
import io.github.madaai.idlestow.core.model.Enums.ActivityClass;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.OptStatus;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.util.PathUtil;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Folder tree, virtualized table with server-side sorting/paging, detail panel and file actions. */
public final class ExplorerView extends BaseView {
    private static final int PAGE = 500;

    /** Folder shown in the tree. */
    private record Folder(Path path, String name, long rootId, boolean revoked) { }

    private final class FolderItem extends TreeItem<Folder> {
        private boolean loaded;
        private boolean loading;

        FolderItem(Folder f) {
            super(f);
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public ObservableList<TreeItem<Folder>> getChildren() {
            if (!loaded && !loading) fetch(null);
            return super.getChildren();
        }

        void fetch(Runnable after) {
            loading = true;
            Path p = getValue().path();
            Background.supply(() -> ctx.engine().queries().list(p, null, Queries.Filter.ALL, Queries.SortField.NAME,
                    true, 0, 3000)).whenComplete((rows, err) -> {
                loading = false;
                loaded = true;
                if (err == null) merge(rows);
                if (after != null) after.run();
            });
        }

        private void merge(List<Queries.Row> rows) {
            Map<Path, FolderItem> existing = new HashMap<>();
            for (TreeItem<Folder> c : super.getChildren()) existing.put(c.getValue().path(), (FolderItem) c);
            List<TreeItem<Folder>> next = new ArrayList<>();
            for (Queries.Row r : rows) {
                IndexedItem it = r.item();
                if (!it.directory()) break; // folders are listed first
                if (!it.present() || it.special()) continue;
                FolderItem old = existing.get(it.path());
                next.add(old != null ? old : new FolderItem(new Folder(it.path(), it.name(), it.rootId(),
                        getValue().revoked())));
            }
            boolean same = next.size() == super.getChildren().size();
            for (int i = 0; same && i < next.size(); i++) same = next.get(i) == super.getChildren().get(i);
            if (!same) super.getChildren().setAll(next);
            for (TreeItem<Folder> c : super.getChildren()) {
                FolderItem fi = (FolderItem) c;
                if (fi.loaded && fi.isExpanded()) fi.fetch(null);
            }
        }

        void reloadIfLoaded() {
            if (loaded && !loading) fetch(null);
        }
    }

    private final TreeItem<Folder> treeRoot = new TreeItem<>();
    private final TreeView<Folder> tree = new TreeView<>(treeRoot);
    private final ObservableList<Queries.Row> rows = FXCollections.observableArrayList();
    private final TableView<Queries.Row> table = new TableView<>(rows);
    private final TextField search = new TextField();
    private final ComboBox<Queries.Filter> filter = new ComboBox<>(
            FXCollections.observableArrayList(Queries.Filter.values()));
    private final HBox breadcrumb = new HBox(2);
    private final Label pageInfo = UI.label("", "muted", "small");
    private final Button loadMore = UI.button("Load more", Icons.CHEVRON_DOWN, "flat");
    private final VBox detail = new VBox(10);
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(320));
    private final PauseTransition detailDelay = new PauseTransition(Duration.millis(140));
    private final VBox placeholder = new VBox(8);
    private final Button optimizeBtn = UI.button("Optimize...", Icons.COMPRESS, "primary");
    private final Button openBtn = UI.button("Open", Icons.OPEN_EXTERNAL);
    private final Button openFolderBtn = UI.button("Show in folder", Icons.FOLDER_OPEN);
    private final Button decompressBtn = UI.button("Decompress", Icons.EXPLORER);
    private final Button restoreBtn = UI.button("Restore", Icons.RECOVERY);
    private final MenuButton moreBtn = new MenuButton("More");
    private final List<MenuItem> contextItems = new ArrayList<>();
    private final Map<String, MenuItem> actions = new HashMap<>();
    private final List<TableColumn<Queries.Row, ?>> allColumns = new ArrayList<>();

    private List<MonitoredRoot> roots = List.of();
    private Path currentFolder;
    private int loadSeq;
    private boolean loadingPage;
    private boolean allLoaded;
    private boolean searchHint;
    private int detailSeq;

    public ExplorerView(AppContext ctx) {
        super(ctx);
        configureTree();
        configureTable();
        configureActions();

        search.setPromptText("Search this folder and everything below it");
        search.setPrefWidth(300);
        search.textProperty().addListener((o, a, b) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(e -> reloadTable());
        filter.setValue(Queries.Filter.ALL);
        filter.valueProperty().addListener((o, a, b) -> reloadTable());
        filter.setTooltip(UI.tip("Filter the files shown"));
        loadMore.setOnAction(e -> loadPage(false));
        loadMore.setVisible(false);
        loadMore.setManaged(false);
        detailDelay.setOnFinished(e -> showDetails());

        HBox filters = UI.hbox(8, search, filter, columnsMenu(), UI.spacer(), pageInfo, loadMore);
        HBox actionBar = UI.hbox(8, optimizeBtn, openBtn, openFolderBtn, decompressBtn, restoreBtn, moreBtn);
        FlowPane flow = new FlowPane(8, 6);
        flow.getChildren().addAll(optimizeBtn, openBtn, openFolderBtn, decompressBtn, restoreBtn, moreBtn);
        actionBar.getChildren().clear();
        VBox center = new VBox(8, breadcrumb, filters, flow, table);
        center.setPadding(new Insets(12, 14, 12, 14));
        VBox.setVgrow(table, Priority.ALWAYS);

        ScrollPane detailScroll = new ScrollPane(detail);
        detailScroll.setFitToWidth(true);
        detail.setPadding(new Insets(14));
        detailScroll.setMinWidth(260);
        tree.setMinWidth(180);
        SplitPane split = new SplitPane(tree, center, detailScroll);
        split.setDividerPositions(0.17, 0.77);
        SplitPane.setResizableWithParent(tree, false);
        SplitPane.setResizableWithParent(detailScroll, false);
        setContent(split);
        updateActions();
        showDetails();
    }

    @Override
    public String title() {
        return "Explorer";
    }

    @Override
    public boolean focusSearch() {
        search.requestFocus();
        search.selectAll();
        return true;
    }

    @Override
    protected long refreshIntervalMs() {
        return 1500;
    }

    @Override
    protected boolean relevant(IdleStowEngine.Event e) {
        return e != IdleStowEngine.Event.SCAN || !ctx.engine().scanStatus().running();
    }

    // ------------------------------------------------------------------ tree

    private void configureTree() {
        tree.setShowRoot(false);
        tree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(Folder f, boolean empty) {
                super.updateItem(f, empty);
                if (empty || f == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(f.name() + (f.revoked() ? " (revoked)" : ""));
                    setGraphic(Icons.EXPLORER.node(16));
                    setTooltip(UI.tip(f.path().toString()));
                }
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && b.getValue() != null && !b.getValue().path().equals(currentFolder)) {
                setFolder(b.getValue().path(), false);
            }
        });
        tree.setAccessibleText("Monitored folders");
    }

    /** Rebuilds top-level items only when the set of roots changed (keeps expansion state otherwise). */
    private void applyRoots(List<MonitoredRoot> list) {
        boolean same = list.size() == roots.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).id() == roots.get(i).id() && list.get(i).active() == roots.get(i).active();
        }
        roots = list;
        if (!same) {
            List<TreeItem<Folder>> items = new ArrayList<>();
            for (MonitoredRoot r : list) {
                FolderItem fi = new FolderItem(new Folder(r.path(), r.displayName(), r.id(), !r.active()));
                items.add(fi);
            }
            treeRoot.getChildren().setAll(items);
        } else {
            for (TreeItem<Folder> c : treeRoot.getChildren()) ((FolderItem) c).reloadIfLoaded();
        }
        if (currentFolder == null && !list.isEmpty()) {
            setFolder(list.get(0).path(), false);
            tree.getSelectionModel().select(treeRoot.getChildren().get(0));
        }
        if (list.isEmpty()) {
            currentFolder = null;
            updateBreadcrumb();
        }
    }

    /** Selects the folder in the tree, expanding and loading ancestors as needed (best effort). */
    private void syncTree(Path target) {
        for (TreeItem<Folder> c : treeRoot.getChildren()) {
            if (PathUtil.isWithin(target, c.getValue().path())) {
                descend((FolderItem) c, target);
                return;
            }
        }
        tree.getSelectionModel().clearSelection();
    }

    private void descend(FolderItem item, Path target) {
        if (PathUtil.key(item.getValue().path()).equals(PathUtil.key(target))) {
            tree.getSelectionModel().select(item);
            tree.scrollTo(tree.getRow(item));
            return;
        }
        item.setExpanded(true);
        Runnable next = () -> {
            for (TreeItem<Folder> c : item.getChildren()) {
                if (PathUtil.isWithin(target, c.getValue().path())) {
                    descend((FolderItem) c, target);
                    return;
                }
            }
        };
        if (item.loaded) next.run();
        else item.fetch(next);
    }

    // ------------------------------------------------------------------ navigation

    /** Public entry for other views ("show in Explorer"). */
    public void navigateTo(Path path) {
        Path dir = path;
        if (!java.nio.file.Files.isDirectory(path)) dir = path.getParent();
        if (dir != null) setFolder(dir, true);
    }

    private void setFolder(Path p, boolean syncTree) {
        currentFolder = p;
        search.clear();
        updateBreadcrumb();
        if (syncTree) syncTree(p);
        reloadTable();
    }

    private void updateBreadcrumb() {
        breadcrumb.getChildren().clear();
        breadcrumb.setAlignment(Pos.CENTER_LEFT);
        if (currentFolder == null) {
            breadcrumb.getChildren().add(UI.label("No folder selected", "muted"));
            return;
        }
        MonitoredRoot root = rootOf(currentFolder);
        Path base = root == null ? currentFolder.getRoot() : root.path();
        List<Path> chain = new ArrayList<>();
        for (Path p = currentFolder; p != null; p = p.getParent()) {
            chain.add(0, p);
            if (base != null && PathUtil.key(p).equals(PathUtil.key(base))) break;
        }
        for (int i = 0; i < chain.size(); i++) {
            Path p = chain.get(i);
            String name = i == 0 && root != null ? root.displayName()
                    : p.getFileName() == null ? p.toString() : p.getFileName().toString();
            Hyperlink link = new Hyperlink(name);
            link.setOnAction(e -> setFolder(p, true));
            link.setFocusTraversable(true);
            breadcrumb.getChildren().add(link);
            if (i < chain.size() - 1) breadcrumb.getChildren().add(UI.label("›", "breadcrumb-sep"));
        }
    }

    private MonitoredRoot rootOf(Path p) {
        for (MonitoredRoot r : roots) if (PathUtil.isWithin(p, r.path())) return r;
        return null;
    }

    // ------------------------------------------------------------------ loading

    @Override
    protected CompletableFuture<Void> load() {
        return Background.supply(() -> ctx.engine().roots()).thenCompose(list -> {
            applyRoots(list);
            return loadPageInternal(true, Math.max(PAGE, rows.size()));
        });
    }

    private void reloadTable() {
        loadPageInternal(true, PAGE).exceptionally(t -> null);
    }

    private void loadPage(boolean reset) {
        loadPageInternal(reset, PAGE).exceptionally(t -> null);
    }

    private CompletableFuture<Void> loadPageInternal(boolean reset, int limit) {
        if (!reset && (loadingPage || allLoaded)) return CompletableFuture.completedFuture(null);
        int seq = ++loadSeq;
        Path folder = currentFolder;
        String q = search.getText();
        Queries.Filter f = filter.getValue();
        Queries.SortField sf = Queries.SortField.NAME;
        boolean asc = true;
        if (!table.getSortOrder().isEmpty()) {
            TableColumn<Queries.Row, ?> c = table.getSortOrder().get(0);
            if (c.getUserData() instanceof Queries.SortField s) {
                sf = s;
                asc = c.getSortType() == TableColumn.SortType.ASCENDING;
                if (sf == Queries.SortField.ACTIVITY) asc = !asc; // "last activity" ascending = longest idle first
            }
        }
        Queries.SortField fsf = sf;
        boolean fasc = asc;
        int offset = reset ? 0 : rows.size();
        if (reset && folder == null) {
            rows.clear();
            allLoaded = true;
            updatePlaceholder();
            updateFooter();
            return CompletableFuture.completedFuture(null);
        }
        loadingPage = true;
        Set<Long> keep = new HashSet<>();
        for (Queries.Row r : table.getSelectionModel().getSelectedItems()) keep.add(r.item().id());
        return Background.supply(() -> ctx.engine().queries().list(folder, q, f, fsf, fasc, offset, limit))
                .whenComplete((list, err) -> {
                    if (seq != loadSeq) return;
                    loadingPage = false;
                    if (err != null) return;
                    if (reset) {
                        rows.setAll(list);
                        table.getSelectionModel().clearSelection();
                        for (int i = 0; i < rows.size(); i++) {
                            if (keep.contains(rows.get(i).item().id())) table.getSelectionModel().select(i);
                        }
                    } else {
                        rows.addAll(list);
                    }
                    allLoaded = list.size() < limit;
                    searchHint = !q.isBlank();
                    updatePlaceholder();
                    updateFooter();
                }).thenApply(l -> (Void) null);
    }

    private void updateFooter() {
        pageInfo.setText(rows.isEmpty() ? "" : String.format("%,d items%s", rows.size(), allLoaded ? "" : " loaded"));
        loadMore.setVisible(!allLoaded && !rows.isEmpty());
        loadMore.setManaged(loadMore.isVisible());
    }

    private void updatePlaceholder() {
        placeholder.getChildren().clear();
        placeholder.setAlignment(Pos.CENTER);
        if (roots.isEmpty()) {
            placeholder.getChildren().add(UI.emptyState(Icons.FOLDER_OPEN, "No monitored folders yet",
                    "Add a folder to browse it here. IdleStow only indexes folders you authorize.",
                    UI.button("Add a folder", Icons.PLUS, () -> Actions.addFolder(ctx, owner(), true, r -> refresh()),
                            "primary")));
        } else if (searchHint || filter.getValue() != Queries.Filter.ALL) {
            placeholder.getChildren().add(UI.emptyState(Icons.SEARCH, "No matching files",
                    "Nothing in this folder matches the current search or filter. Files appear after the folder"
                            + " has been scanned (F5)."));
        } else {
            placeholder.getChildren().add(UI.emptyState(Icons.EXPLORER, "Nothing indexed here",
                    "This folder is empty or has not been scanned yet. Press F5 to scan."));
        }
    }

    // ------------------------------------------------------------------ table

    private void configureTable() {
        table.setPlaceholder(placeholder);
        table.setSortPolicy(t -> true); // sorting is performed by the database
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setTableMenuButtonVisible(false);
        table.setAccessibleText("Files and folders");

        TableColumn<Queries.Row, Queries.Row> name = new TableColumn<>("Name");
        name.setPrefWidth(280);
        name.setCellValueFactory(cd -> new javafx.beans.property.SimpleObjectProperty<>(cd.getValue()));
        name.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Queries.Row r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                IndexedItem it = r.item();
                setText(it.name());
                setGraphic((it.directory() ? Icons.EXPLORER : Icons.FILE).node(16));
                setTooltip(UI.tip(it.path().toString()));
            }
        });
        name.setUserData(Queries.SortField.NAME);
        allColumns.add(name);

        TableColumn<Queries.Row, String> type = UI.col("Type", 100, ExplorerView::typeOf);
        type.setUserData(Queries.SortField.TYPE);
        TableColumn<Queries.Row, String> size = UI.numCol("Size", 90, r -> r.item().directory()
                ? Fmt.size(r.dirLogical()) : Fmt.size(r.item().logicalSize()));
        size.setUserData(Queries.SortField.SIZE);
        TableColumn<Queries.Row, String> phys = UI.numCol("Size on disk", 100, ExplorerView::physical);
        phys.setUserData(Queries.SortField.PHYSICAL);
        TableColumn<Queries.Row, String> pot = UI.numCol("Potential savings (est.)", 150, ExplorerView::potential);
        pot.setUserData(Queries.SortField.POTENTIAL);
        TableColumn<Queries.Row, String> act = UI.numCol("Actual savings", 110, ExplorerView::actual);
        act.setSortable(false);
        TableColumn<Queries.Row, String> last = UI.col("Last activity", 160, r -> r.item().directory() ? ""
                : r.lastActivityMs() > 0 ? Fmt.epochMs(r.lastActivityMs()) : "unknown");
        last.setUserData(Queries.SortField.ACTIVITY);
        TableColumn<Queries.Row, String> conf = UI.col("Confidence", 90, r -> r.item().directory() ? ""
                : Fmt.title(r.confidence()));
        conf.setSortable(false);
        TableColumn<Queries.Row, String> status = UI.col("Status", 190, ExplorerView::statusOf);
        status.setUserData(Queries.SortField.STATUS);
        allColumns.addAll(List.of(type, size, phys, pot, act, last, conf, status));
        table.getColumns().add(name);
        table.getColumns().addAll(type, size, phys, pot, act, last, conf, status);
        String hidden = ctx.prefs().getString("explorer.hiddenColumns", "");
        for (TableColumn<Queries.Row, ?> c : table.getColumns()) {
            if (("," + hidden + ",").contains("," + c.getText() + ",")) c.setVisible(false);
        }
        table.getSortOrder().addListener((ListChangeListener<TableColumn<Queries.Row, ?>>) c -> reloadTable());
        table.skinProperty().addListener((o, a, b) -> Platform.runLater(this::hookScroll));

        table.setRowFactory(tv -> {
            TableRow<Queries.Row> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()) {
                    activate(row.getItem());
                }
            });
            return row;
        });
        table.getSelectionModel().getSelectedItems().addListener((ListChangeListener<Queries.Row>) c -> {
            updateActions();
            detailDelay.playFromStart();
        });
        table.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE && e.isShortcutDown()) {
                e.consume();
                toast("IdleStow never deletes your files.");
            } else if (e.getCode() == KeyCode.ENTER && !selected().isEmpty()) {
                e.consume();
                activate(selected().get(0));
            } else if (new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN).match(e)) {
                e.consume();
                copyPaths();
            }
        });
        table.setOnContextMenuRequested(e -> updateActions());
    }

    private void hookScroll() {
        for (Node n : table.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL) {
                sb.valueProperty().addListener((o, a, b) -> {
                    if (!allLoaded && !loadingPage && b.doubleValue() > 0.85 * sb.getMax()) loadPage(false);
                });
            }
        }
    }

    private MenuButton columnsMenu() {
        MenuButton mb = new MenuButton("Columns");
        mb.setGraphic(Icons.LIST.node(16));
        mb.setTooltip(UI.tip("Choose visible columns"));
        for (TableColumn<Queries.Row, ?> c : table.getColumns()) {
            CheckMenuItem mi = new CheckMenuItem(c.getText());
            mi.setSelected(c.isVisible());
            mi.selectedProperty().addListener((o, a, b) -> {
                c.setVisible(b);
                StringBuilder sb = new StringBuilder();
                for (TableColumn<Queries.Row, ?> x : table.getColumns()) {
                    if (!x.isVisible()) sb.append(sb.isEmpty() ? "" : ",").append(x.getText());
                }
                ctx.prefs().put("explorer.hiddenColumns", sb.toString());
            });
            mb.getItems().add(mi);
        }
        return mb;
    }

    private static String typeOf(Queries.Row r) {
        IndexedItem it = r.item();
        if (it.directory()) return "Folder";
        String ext = it.extension() == null || it.extension().isBlank() ? "" : " (." + it.extension() + ")";
        return Fmt.category(it.category()) + ext;
    }

    private static String physical(Queries.Row r) {
        IndexedItem it = r.item();
        if (it.directory()) return Fmt.size(r.dirPhysical());
        if (it.physicalSize() < 0) return Fmt.DASH;
        return (it.physicalExact() ? "" : "~") + Fmt.size(it.physicalSize());
    }

    private static String potential(Queries.Row r) {
        if (r.item().directory()) return Fmt.estimate(r.dirEstReclaimable());
        return Fmt.estimate(r.candidateSavings());
    }

    /** Measured savings only: allocation difference of natively compressed files, aggregate for folders. */
    private static String actual(Queries.Row r) {
        IndexedItem it = r.item();
        if (it.directory()) return r.dirActualReclaimed() == null || r.dirActualReclaimed() <= 0 ? Fmt.DASH
                : Fmt.size(r.dirActualReclaimed());
        if (it.nativeCompressed() && it.physicalExact() && it.physicalSize() >= 0 && it.physicalSize() < it.logicalSize()) {
            return Fmt.size(it.logicalSize() - it.physicalSize());
        }
        return Fmt.DASH;
    }

    private static String statusOf(Queries.Row r) {
        IndexedItem it = r.item();
        if (it.directory()) {
            return r.dirFiles() == null ? "" : r.dirFiles() + " files"
                    + (r.dirInactive() != null && r.dirInactive() > 0 ? ", " + r.dirInactive() + " inactive" : "");
        }
        if (it.optStatus() != OptStatus.NONE) return it.optStatus().label();
        return activityLabel(r.classification());
    }

    private static String activityLabel(String cls) {
        if (cls == null) return "Not analyzed";
        try {
            return ActivityClass.valueOf(cls).label();
        } catch (IllegalArgumentException e) {
            return Fmt.title(cls);
        }
    }

    private void activate(Queries.Row r) {
        IndexedItem it = r.item();
        if (it.directory()) {
            setFolder(it.path(), true);
        } else {
            openSelected();
        }
    }

    // ------------------------------------------------------------------ actions

    private List<Queries.Row> selected() {
        return new ArrayList<>(table.getSelectionModel().getSelectedItems());
    }

    private void configureActions() {
        openBtn.setOnAction(e -> openSelected());
        openFolderBtn.setOnAction(e -> revealSelected());
        optimizeBtn.setOnAction(e -> optimizeSelected());
        decompressBtn.setOnAction(e -> decompressSelected());
        restoreBtn.setOnAction(e -> restoreSelected());

        add("open", "Open", Icons.OPEN_EXTERNAL, this::openSelected);
        add("reveal", "Open containing folder", Icons.FOLDER_OPEN, this::revealSelected);
        add("copy", "Copy path", Icons.COPY, this::copyPaths);
        contextItems.add(new SeparatorMenuItem());
        add("optimize", "Optimize selected...", Icons.COMPRESS, this::optimizeSelected);
        add("decompress", "Decompress", Icons.EXPLORER, this::decompressSelected);
        add("restore", "Restore archived file...", Icons.RECOVERY, this::restoreSelected);
        contextItems.add(new SeparatorMenuItem());
        add("inspect", "Inspect activity", Icons.EYE, this::inspectSelected);
        add("refresh", "Refresh metadata (rescan folder)", Icons.REFRESH, this::refreshMetadata);
        add("pin", "Pin / unpin", Icons.PIN, this::togglePin);
        add("exclude", "Exclude from IdleStow...", Icons.X, this::excludeSelected);
        add("active", "Mark as active", Icons.BOLT, () -> declare(true));
        add("inactive", "Confirm inactive", Icons.CHECK, () -> declare(false));
        contextItems.add(new SeparatorMenuItem());
        add("history", "View history", Icons.HISTORY, this::viewHistory);

        moreBtn.getItems().addAll(contextItems.stream().map(this::copyOf).toList());
        table.setContextMenu(new ContextMenu(contextItems.toArray(new MenuItem[0])));
    }

    private MenuItem copyOf(MenuItem mi) {
        if (mi instanceof SeparatorMenuItem) return new SeparatorMenuItem();
        MenuItem c = new MenuItem(mi.getText());
        c.setOnAction(mi.getOnAction());
        c.setGraphic(null);
        // keep the twin in sync with its source item
        c.disableProperty().bind(mi.disableProperty());
        return c;
    }

    private void add(String key, String text, Icons icon, Runnable run) {
        MenuItem mi = new MenuItem(text);
        mi.setGraphic(icon.node(16));
        mi.setOnAction(e -> run.run());
        actions.put(key, mi);
        contextItems.add(mi);
    }

    private void updateActions() {
        List<Queries.Row> sel = selected();
        boolean any = !sel.isEmpty();
        boolean single = sel.size() == 1;
        boolean readOnly = ctx.engine().readOnly();
        boolean anyFile = sel.stream().anyMatch(r -> !r.item().directory());
        boolean anyNative = sel.stream().anyMatch(r -> r.item().nativeCompressed());
        boolean anyArchived = sel.stream().anyMatch(r -> r.item().optStatus() == OptStatus.ARCHIVED);

        enable(openBtn, "open", single, "Select one file or folder");
        enable(openFolderBtn, "reveal", single, "Select one item");
        enable(optimizeBtn, "optimize", any && !readOnly, readOnly ? "Read-only mode: files are never changed"
                : "Select files or folders to optimize");
        enable(decompressBtn, "decompress", anyNative && !readOnly, readOnly ? "Read-only mode"
                : "Select natively compressed files");
        enable(restoreBtn, "restore", anyArchived && !readOnly, readOnly ? "Read-only mode"
                : "Select files that were replaced by an archive");
        enable(null, "copy", any, null);
        enable(null, "inspect", single, null);
        enable(null, "refresh", any, null);
        enable(null, "pin", any, null);
        enable(null, "exclude", any, null);
        enable(null, "active", anyFile, null);
        enable(null, "inactive", anyFile, null);
        enable(null, "history", single, null);
    }

    private void enable(Button b, String key, boolean on, String reason) {
        MenuItem mi = actions.get(key);
        if (mi != null) mi.setDisable(!on);
        if (b != null) {
            b.setDisable(!on);
            UI.tooltip(b, on ? null : reason);
        }
    }

    private Path pathOf(Queries.Row r) {
        return r.item().path();
    }

    private void copyPaths() {
        List<Queries.Row> sel = selected();
        if (sel.isEmpty()) return;
        Actions.copyText(String.join(System.lineSeparator(), sel.stream().map(r -> pathOf(r).toString()).toList()));
        toast("Copied " + Fmt.plural(sel.size(), "path"));
    }

    private void openSelected() {
        List<Queries.Row> sel = selected();
        if (sel.size() != 1) return;
        IndexedItem it = sel.get(0).item();
        if (it.directory()) {
            setFolder(it.path(), true);
            return;
        }
        if (it.optStatus() == OptStatus.ARCHIVED || !it.present()) {
            if (Dialogs.confirm(owner(), "Restore first", "This file is stored in an archive",
                    "The original was replaced by a verified archive, so it cannot be opened directly. Restore it"
                            + " first.", "Restore...")) {
                restoreSelected();
            }
            return;
        }
        Actions.openExternal(owner(), it.path(), () -> {
            try {
                ctx.engine().recordOpened(it.id());
            } catch (java.sql.SQLException e) {
                // activity evidence is optional
            }
        });
    }

    private void revealSelected() {
        List<Queries.Row> sel = selected();
        if (sel.size() == 1) Actions.reveal(owner(), pathOf(sel.get(0)));
    }

    private void optimizeSelected() {
        List<Long> ids = selected().stream().map(r -> r.item().id()).toList();
        if (!ids.isEmpty()) ctx.nav().openPlanPreview(ids);
    }

    private void decompressSelected() {
        List<Long> ids = selected().stream().filter(r -> r.item().nativeCompressed()).map(r -> r.item().id()).toList();
        if (ids.isEmpty()) return;
        if (!Dialogs.confirm(owner(), "Decompress", "Decompress " + Fmt.plural(ids.size(), "file") + "?",
                "The files are stored uncompressed again and use more disk space. Their content is verified"
                        + " unchanged.", "Decompress")) return;
        Background.supply(() -> ctx.engine().decompress(ids)).whenComplete((job, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot decompress", Background.message(err));
            else Actions.jobStarted(ctx, job, "Decompression");
        });
    }

    private void restoreSelected() {
        List<Long> ids = selected().stream().filter(r -> r.item().optStatus() == OptStatus.ARCHIVED)
                .map(r -> r.item().id()).toList();
        if (ids.isEmpty()) return;
        if (ids.size() > 1) {
            ctx.nav().showRecovery(null);
            toast("Select the archives to restore in Recovery");
            return;
        }
        Background.supply(() -> ctx.engine().recoveryRecords().currentForItem(ids.get(0))).whenComplete((rec, err) -> {
            if (err != null) {
                Dialogs.error(owner(), "Cannot restore", Background.message(err));
            } else if (rec.isEmpty()) {
                Dialogs.info(owner(), "Restore", "No recovery record", "No recovery record exists for this file.");
            } else {
                Actions.restore(ctx, owner(), rec.get());
            }
        });
    }

    private void inspectSelected() {
        List<Queries.Row> sel = selected();
        if (sel.size() != 1) return;
        Queries.Row r = sel.get(0);
        if (r.item().directory()) {
            Dialogs.info(owner(), "Inspect activity", r.item().name(), "Folders are not classified. IdleStow"
                    + " classifies the files inside; see the folder summary in the details panel.");
            return;
        }
        Background.supply(() -> ctx.engine().classification(r.item().id())).whenComplete((a, err) -> {
            if (err != null) {
                Dialogs.error(owner(), "Cannot inspect", Background.message(err));
                return;
            }
            Dialogs.textView(owner(), "Activity evidence - " + r.item().name(), activityText(r, a.orElse(null)));
        });
    }

    private static String activityText(Queries.Row r, ActivityAssessment a) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.item().path()).append("\n\n");
        if (a == null) {
            sb.append("This file has not been analyzed yet. Scan the folder (F5).");
            return sb.toString();
        }
        sb.append("Classification: ").append(a.classification().label()).append('\n');
        sb.append("Confidence:     ").append(Fmt.title(a.confidence().name())).append('\n');
        sb.append("Last activity:  ").append(a.lastActivityMs() > 0 ? Fmt.epochMs(a.lastActivityMs()) : "unknown")
                .append('\n');
        sb.append("Idle:           ").append(Fmt.idle(a.idleDays())).append("\n\nEvidence used:\n");
        for (String s : a.reasons()) sb.append("  - ").append(s).append('\n');
        return sb.toString();
    }

    private void refreshMetadata() {
        Set<Long> rootIds = new HashSet<>();
        for (Queries.Row r : selected()) rootIds.add(r.item().rootId());
        for (Long id : rootIds) ctx.engine().scan(id);
        toast("Rescanning " + Fmt.plural(rootIds.size(), "monitored folder") + " (read-only)");
    }

    private void togglePin() {
        List<Queries.Row> sel = selected();
        boolean unpin = !sel.isEmpty() && sel.stream().allMatch(r -> "PINNED".equals(r.classification()));
        List<Path> paths = sel.stream().map(this::pathOf).toList();
        Background.run(() -> {
            for (Path p : paths) {
                if (unpin) ctx.engine().rules().unpin(p);
                else ctx.engine().rules().pin(p);
            }
            ctx.engine().rulesChanged();
        }).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot change pins", Background.message(err));
            else toast(unpin ? "Unpinned" : "Pinned: IdleStow will never change pinned items");
        });
    }

    private void excludeSelected() {
        List<Path> paths = selected().stream().map(this::pathOf).toList();
        if (paths.isEmpty()) return;
        if (!Dialogs.confirm(owner(), "Exclude", "Exclude " + Fmt.plural(paths.size(), "item") + "?",
                "Excluded items are never recommended or changed. You can remove exclusions in Settings >"
                        + " Monitoring.", "Exclude")) return;
        Background.run(() -> {
            for (Path p : paths) ctx.engine().rules().addExclusion("PATH", p.toString());
            ctx.engine().rulesChanged();
        }).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot exclude", Background.message(err));
            else toast("Excluded " + Fmt.plural(paths.size(), "item"));
        });
    }

    private void declare(boolean active) {
        List<Long> ids = selected().stream().filter(r -> !r.item().directory()).map(r -> r.item().id()).toList();
        if (ids.isEmpty()) return;
        if (ids.size() > 25) {
            Dialogs.info(owner(), "Too many files", "Select fewer files",
                    "Each declaration re-analyzes the folder. Please select at most 25 files at a time.");
            return;
        }
        Background.run(() -> {
            for (Long id : ids) {
                if (active) ctx.engine().markActive(id);
                else ctx.engine().confirmInactive(id);
            }
        }).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner(), "Cannot record", Background.message(err));
            else toast(active ? "Marked active" : "Confirmed inactive");
        });
    }

    private void viewHistory() {
        List<Queries.Row> sel = selected();
        if (sel.size() == 1) ctx.nav().showHistory(pathOf(sel.get(0)).toString());
    }

    // ------------------------------------------------------------------ detail panel

    private void showDetails() {
        List<Queries.Row> sel = selected();
        int seq = ++detailSeq;
        detail.getChildren().clear();
        if (sel.isEmpty()) {
            detail.getChildren().add(UI.emptyState(Icons.INFO, "Details", "Select a file or folder to see its"
                    + " metadata, activity evidence and compression state."));
            return;
        }
        if (sel.size() > 1) {
            long logical = sel.stream().filter(r -> !r.item().directory()).mapToLong(r -> r.item().logicalSize()).sum();
            long est = sel.stream().filter(r -> r.candidateSavings() != null).mapToLong(Queries.Row::candidateSavings).sum();
            detail.getChildren().addAll(UI.label(sel.size() + " items selected", "h2"),
                    kv("Total size (files)", Fmt.size(logical)), kv("Estimated savings", Fmt.estimate(est)),
                    UI.muted("Use Optimize... to review exactly what would change before anything is touched."));
            return;
        }
        Queries.Row r = sel.get(0);
        IndexedItem it = r.item();
        detail.getChildren().add(UI.hbox(8, (it.directory() ? Icons.EXPLORER : Icons.FILE).node(22, "icon-accent"),
                UI.wrapped(it.name(), "h2")));
        GridPane g = grid();
        int row = 0;
        row = kv(g, row, "Path", it.path().toString());
        row = kv(g, row, "Type", typeOf(r));
        if (!it.directory()) {
            row = kv(g, row, "Logical size", Fmt.size(it.logicalSize()));
            row = kv(g, row, "Size on disk", physical(r) + (it.physicalExact() ? "" : " (approximate)"));
        } else {
            row = kv(g, row, "Logical size", Fmt.size(r.dirLogical()));
            row = kv(g, row, "Size on disk", Fmt.size(r.dirPhysical()));
        }
        row = kv(g, row, "Modified", Fmt.epochMs(it.modifiedMs()));
        row = kv(g, row, "Created", Fmt.epochMs(it.createdMs()));
        row = kv(g, row, "Accessed", it.accessedMs() > 0 ? Fmt.epochMs(it.accessedMs()) : "not tracked");
        if (!it.directory()) {
            row = kv(g, row, "Compression", it.nativeCompressed() ? "Native compression active"
                    : it.optStatus().label());
            row = kv(g, row, "Attributes", it.attrFlags().isEmpty() ? "none" : String.join(", ", it.attrFlags()));
        }
        row = kv(g, row, "Indexed", Fmt.iso(it.lastScannedUtc()));
        if (it.error() != null && !it.error().isBlank()) kv(g, row, "Scan note", it.error());
        detail.getChildren().add(g);

        if (it.directory()) {
            detail.getChildren().add(UI.label("Folder summary", "section-title"));
            GridPane d = grid();
            long files = r.dirFiles() == null ? 0 : r.dirFiles();
            long act = r.dirActive() == null ? 0 : r.dirActive();
            long inact = r.dirInactive() == null ? 0 : r.dirInactive();
            int dr = 0;
            dr = kv(d, dr, "Files", String.format("%,d", files));
            dr = kv(d, dr, "Active", String.format("%,d", act));
            dr = kv(d, dr, "Inactive", String.format("%,d", inact));
            dr = kv(d, dr, "Unknown / other", String.format("%,d", Math.max(0, files - act - inact)));
            dr = kv(d, dr, "Estimated reclaimable", Fmt.estimate(r.dirEstReclaimable()));
            kv(d, dr, "Actual reclaimed", r.dirActualReclaimed() == null || r.dirActualReclaimed() <= 0 ? Fmt.DASH
                    : Fmt.size(r.dirActualReclaimed()));
            detail.getChildren().add(d);
            return;
        }

        detail.getChildren().add(UI.label("Activity", "section-title"));
        Label loading = UI.muted("Loading evidence...");
        detail.getChildren().add(loading);
        Background.supply(() -> new Object[]{ctx.engine().classification(it.id()),
                ctx.engine().recoveryRecords().currentForItem(it.id())}).whenComplete((res, err) -> {
            if (seq != detailSeq) return;
            detail.getChildren().remove(loading);
            if (err != null) {
                detail.getChildren().add(UI.banner(Icons.WARNING, Background.message(err), "error"));
                return;
            }
            @SuppressWarnings("unchecked")
            java.util.Optional<ActivityAssessment> a = (java.util.Optional<ActivityAssessment>) res[0];
            @SuppressWarnings("unchecked")
            java.util.Optional<RecoveryRepository.Record> rec = (java.util.Optional<RecoveryRepository.Record>) res[1];
            GridPane ag = grid();
            int ar = 0;
            if (a.isPresent()) {
                ActivityAssessment x = a.get();
                ar = kv(ag, ar, "Classification", x.classification().label());
                ar = kv(ag, ar, "Confidence", Fmt.title(x.confidence().name()));
                ar = kv(ag, ar, "Last activity", x.lastActivityMs() > 0 ? Fmt.epochMs(x.lastActivityMs()) : "unknown");
                ar = kv(ag, ar, "Idle", Fmt.idle(x.idleDays()));
                detail.getChildren().add(ag);
                detail.getChildren().add(UI.label("Why", "section-title"));
                for (String reason : x.reasons()) detail.getChildren().add(UI.wrapped("• " + reason, "detail-value"));
            } else {
                detail.getChildren().add(UI.muted("Not analyzed yet. Scan the folder (F5)."));
            }
            if (r.candidateSavings() != null) {
                detail.getChildren().add(UI.label("Recommendation", "section-title"));
                GridPane rg = grid();
                int rr = kv(rg, 0, "Method", r.candidateMethod() == null ? Fmt.DASH
                        : methodLabel(r.candidateMethod()));
                kv(rg, rr, "Estimated savings", Fmt.estimate(r.candidateSavings()) + " (estimate)");
                detail.getChildren().add(rg);
            }
            if (rec.isPresent()) {
                RecoveryRepository.Record rc = rec.get();
                detail.getChildren().add(UI.label("Recovery", "section-title"));
                GridPane cg = grid();
                int cr = kv(cg, 0, "Kind", Fmt.title(rc.kind().name()));
                cr = kv(cg, cr, "Status", Fmt.title(rc.status().name()));
                if (rc.archivePath() != null) cr = kv(cg, cr, "Archive", rc.archivePath());
                if (rc.archiveSize() != null) cr = kv(cg, cr, "Archive size", Fmt.size(rc.archiveSize()));
                if (rc.retainUntilUtc() != null) kv(cg, cr, "Original kept until", Fmt.iso(rc.retainUntilUtc()));
                detail.getChildren().add(cg);
            }
        });
    }

    private static String methodLabel(String name) {
        try {
            return Method.valueOf(name).label();
        } catch (IllegalArgumentException e) {
            return Fmt.title(name);
        }
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(5);
        javafx.scene.layout.ColumnConstraints a = new javafx.scene.layout.ColumnConstraints();
        a.setMinWidth(96);
        a.setPrefWidth(110);
        javafx.scene.layout.ColumnConstraints b = new javafx.scene.layout.ColumnConstraints();
        b.setHgrow(Priority.ALWAYS);
        b.setMinWidth(80);
        g.getColumnConstraints().addAll(a, b);
        return g;
    }

    private static int kv(GridPane g, int row, String k, String v) {
        Label key = UI.label(k, "detail-key");
        key.setAlignment(Pos.TOP_LEFT);
        Label val = UI.wrapped(v == null ? Fmt.DASH : v, "detail-value");
        val.setMaxWidth(Double.MAX_VALUE);
        GridPane.setValignment(key, javafx.geometry.VPos.TOP);
        g.add(key, 0, row);
        g.add(val, 1, row);
        return row + 1;
    }

    private static HBox kv(String k, String v) {
        HBox h = new HBox(10, UI.label(k, "detail-key"), UI.label(v, "detail-value"));
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    @SuppressWarnings("unused")
    private void goToRecommendations() {
        ctx.nav().show(ViewId.RECOMMENDATIONS);
    }
}
