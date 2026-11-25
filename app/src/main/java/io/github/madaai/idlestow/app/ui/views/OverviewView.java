package io.github.madaai.idlestow.app.ui.views;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.Actions;
import io.github.madaai.idlestow.app.ui.Icons;
import io.github.madaai.idlestow.app.ui.ViewId;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.Fmt;
import io.github.madaai.idlestow.core.db.Queries;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.model.Enums.ActivityClass;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Dashboard: measured totals, estimates (always labelled) and charts. Works with an empty index. */
public final class OverviewView extends BaseView {

    private record Data(Queries.Totals totals, long reclaimed, long pendingAfter, long pendingSavings,
                        long pendingCount, long activeJobs, long attention, List<MonitoredRoot> roots,
                        List<Object[]> byCategory, List<Object[]> byRoot, List<Object[]> byActivity,
                        List<long[]> savings, List<Object[]> largest, List<Object[]> effectiveness, String mode, boolean paused,
                        boolean backgroundEnabled, long nextScanMs, boolean scanning) { }

    private final VBox content = new VBox(16);
    private final ScrollPane scroll = new ScrollPane(content);

    public OverviewView(AppContext ctx) {
        super(ctx);
        content.setPadding(new Insets(18));
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setContent(scroll);
    }

    @Override
    public String title() {
        return "Overview";
    }

    @Override
    protected long refreshIntervalMs() {
        return 1500;
    }

    @Override
    protected Set<IdleStowEngine.Event> events() {
        return Set.of(IdleStowEngine.Event.ANALYSIS, IdleStowEngine.Event.ROOTS, IdleStowEngine.Event.JOBS,
                IdleStowEngine.Event.SCAN, IdleStowEngine.Event.SETTINGS, IdleStowEngine.Event.RECOVERY);
    }

    @Override
    protected boolean relevant(IdleStowEngine.Event e) {
        return e != IdleStowEngine.Event.SCAN || !ctx.engine().scanStatus().running();
    }

    /** A piece of the page that is rebuilt only when the data it shows changes. */
    private static final class Slot {
        private Object key;
        private Node node;

        boolean update(Object newKey, Supplier<Node> builder) {
            if (node != null && Objects.equals(key, newKey)) return false;
            key = newKey;
            node = builder.get();
            return true;
        }
    }

    private final Slot welcomeSlot = new Slot();
    private final Slot kpiSlot = new Slot();
    private final Slot statusSlot = new Slot();
    private final Slot bannerSlot = new Slot();
    private final Slot largestSlot = new Slot();
    private final Slot[] chartSlots = new Slot[6];
    private final Node[] chartCards = new Node[6];
    private GridPane grid;
    private Data lastData;

    {
        for (int i = 0; i < chartSlots.length; i++) chartSlots[i] = new Slot();
    }

    @Override
    protected CompletableFuture<Void> load() {
        return Background.supply(() -> {
            IdleStowEngine e = ctx.engine();
            Queries q = e.queries();
            return new Data(q.totals(), q.currentReclaimed(), q.pendingAfterRetention(), e.pendingSavings(),
                    e.pendingRecommendationCount(), e.jobs().countActive(), e.recoveryRecords().countNeedingAttention(),
                    e.roots(), q.byCategory(), q.byRoot(), q.byActivity(), e.history().savingsPerDay(90),
                    q.largestCandidates(10), q.effectiveness(), e.settings().mode().label(), e.paused(),
                    e.settings().backgroundEnabled(), e.nextScanAtMs(), e.scanStatus().running());
        }).thenAccept(this::render);
    }

    /** Content equality for row lists (arrays compare by identity otherwise). */
    private static List<String> k(List<?> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (Object r : rows) {
            out.add(r instanceof Object[] a ? Arrays.deepToString(a) : r instanceof long[] l ? Arrays.toString(l)
                    : String.valueOf(r));
        }
        return out;
    }

    private void render(Data d) {
        if (lastData != null && !content.getChildren().isEmpty() && sameData(lastData, d)) return;
        lastData = d;
        boolean noRoots = d.roots().isEmpty();
        if (noRoots) welcomeSlot.update(Boolean.TRUE, this::welcomeCard);
        kpiSlot.update(List.of(d.totals(), d.reclaimed(), d.pendingAfter(), d.pendingSavings(),
                d.pendingCount(), d.activeJobs(), d.attention()), () -> kpis(d));
        statusSlot.update(List.of(d.roots(), d.mode(), d.paused(), d.backgroundEnabled(), d.nextScanMs()),
                () -> statusStrip(d));
        boolean empty = d.totals().files() == 0 && !noRoots;
        if (empty) {
            bannerSlot.update(d.scanning(), () -> UI.banner(Icons.INFO, d.scanning()
                    ? "Indexing is in progress. Charts fill in as files are indexed."
                    : "Nothing is indexed yet. Press F5 (Scan now) to index your monitored folders.", "info"));
        }
        if (grid == null) {
            grid = new GridPane();
            grid.setHgap(16);
            grid.setVgap(16);
            for (int i = 0; i < 2; i++) {
                ColumnConstraints cc = new ColumnConstraints();
                cc.setPercentWidth(50);
                cc.setHgrow(Priority.ALWAYS);
                grid.getColumnConstraints().add(cc);
            }
        }
        chart(0, k(d.byCategory()), "Storage by category", "Measured (logical size)",
                () -> categoryPie(d.byCategory()), 0, 0);
        chart(1, k(d.byRoot()), "Storage by monitored location", "Measured (logical size)",
                () -> rootBars(d.byRoot()), 1, 0);
        chart(2, k(d.byActivity()), "Activity distribution", "Classification (files)",
                () -> activityPie(d.byActivity()), 0, 1);
        chart(3, k(d.byActivity()), "Active vs inactive", "Measured (logical size)",
                () -> activeBars(d.byActivity()), 1, 1);
        chart(4, k(d.savings()), "Measured savings over time", "Cumulative, last 90 days",
                () -> savingsLine(d.savings()), 0, 2);
        chart(5, k(d.effectiveness()), "Compression effectiveness", "Measured per method",
                () -> effectivenessTable(d.effectiveness()), 1, 2);
        largestSlot.update(k(d.largest()), () -> chartCard("Largest recommendations", "Estimates - not guaranteed",
                largestTable(d.largest())));
        List<Node> wanted = new ArrayList<>();
        if (noRoots) wanted.add(welcomeSlot.node);
        wanted.add(kpiSlot.node);
        wanted.add(statusSlot.node);
        if (empty) wanted.add(bannerSlot.node);
        wanted.add(grid);
        wanted.add(largestSlot.node);
        if (!wanted.equals(content.getChildren())) content.getChildren().setAll(wanted);
    }

    private void chart(int i, Object key, String title, String subtitle, Supplier<Node> body, int col, int row) {
        Slot slot = chartSlots[i];
        if (slot.update(key, () -> chartCard(title, subtitle, body.get()))) {
            if (chartCards[i] != null) grid.getChildren().remove(chartCards[i]);
            chartCards[i] = slot.node;
            grid.add(slot.node, col, row);
        }
    }

    private static boolean sameData(Data a, Data b) {
        return a.totals().equals(b.totals()) && a.reclaimed() == b.reclaimed() && a.pendingAfter() == b.pendingAfter()
                && a.pendingSavings() == b.pendingSavings() && a.pendingCount() == b.pendingCount()
                && a.activeJobs() == b.activeJobs() && a.attention() == b.attention() && a.roots().equals(b.roots())
                && a.mode().equals(b.mode()) && a.paused() == b.paused()
                && a.backgroundEnabled() == b.backgroundEnabled() && a.nextScanMs() == b.nextScanMs()
                && a.scanning() == b.scanning() && k(a.byCategory()).equals(k(b.byCategory()))
                && k(a.byRoot()).equals(k(b.byRoot())) && k(a.byActivity()).equals(k(b.byActivity()))
                && k(a.savings()).equals(k(b.savings())) && k(a.largest()).equals(k(b.largest()))
                && k(a.effectiveness()).equals(k(b.effectiveness()));
    }

    private Node welcomeCard() {
        Node cta = UI.button("Add a folder", Icons.PLUS, () -> Actions.addFolder(ctx, owner(), true, r -> refresh()),
                "primary");
        VBox empty = UI.emptyState(Icons.FOLDER_OPEN, "Choose what IdleStow may look at",
                "IdleStow only ever reads folders you authorize. It finds idle files, recommends verified lossless"
                        + " compression and never deletes your files on its own. Add a folder to get your first recommendations.", cta);
        VBox card = new VBox(empty);
        card.getStyleClass().add("card");
        return card;
    }

    private Node kpis(Data d) {
        FlowPane flow = new FlowPane(14, 14);
        Label reclaimed = UI.label(Fmt.size(d.reclaimed()));
        Label potential = UI.label(d.pendingSavings() > 0 ? Fmt.estimate(d.pendingSavings()) : Fmt.DASH);
        Label after = UI.label(Fmt.size(d.pendingAfter()));
        Label files = UI.label(String.format("%,d", d.totals().files()));
        Label jobs = UI.label(Long.toString(d.activeJobs()));
        Label attention = UI.label(Long.toString(d.attention()));
        if (d.attention() > 0) attention.getStyleClass().add("warn-text");
        flow.getChildren().addAll(
                tile(UI.kpi("Reclaimed now", reclaimed, "Measured from file allocation on disk", "Measured", "measured")),
                tile(UI.kpi("Potential savings", potential, Fmt.plural(d.pendingCount(), "recommendation")
                        + " pending", "Estimate", "estimate")),
                tile(UI.kpi("After retention", after, "Extra space freed once retained originals are removed",
                        "Measured", "measured")),
                tile(UI.kpi("Indexed files", files, Fmt.size(d.totals().logical()) + " logical, "
                        + Fmt.size(d.totals().physical()) + " on disk", null, null)),
                tile(UI.kpi("Active jobs", jobs, d.activeJobs() == 0 ? "Nothing running" : "See Jobs for progress",
                        null, null)),
                tile(UI.kpi("Need attention", attention, d.attention() == 0 ? "Recovery is healthy"
                        : "Open Recovery to review", null, null)));
        return flow;
    }

    private static Node tile(Node n) {
        ((VBox) n).setPrefWidth(236);
        return n;
    }

    private Node statusStrip(Data d) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("card-flat");
        String lastScan = d.roots().stream().map(MonitoredRoot::lastScanUtc).filter(s -> s != null)
                .max(String::compareTo).map(Fmt::iso).orElse("never");
        row.getChildren().addAll(
                UI.label("Mode", "detail-key"), UI.badge(d.mode(), "info"),
                UI.label("Background", "detail-key"), UI.badge(d.paused() ? "Paused" : d.backgroundEnabled()
                        ? "On" : "Off", d.paused() ? "warn" : d.backgroundEnabled() ? "ok" : "neutral"),
                UI.label("Last scan", "detail-key"), UI.label(lastScan, "detail-value"),
                UI.label("Next scan", "detail-key"), UI.label(d.nextScanMs() > 0 ? Fmt.epochMs(d.nextScanMs())
                        : "not scheduled", "detail-value"),
                UI.spacer(), UI.button("Open Recommendations", Icons.RECOMMEND,
                        () -> ctx.nav().show(ViewId.RECOMMENDATIONS), "flat"));
        return row;
    }

    private static VBox chartCard(String title, String subtitle, Node content) {
        Label sub = UI.label(subtitle, "muted", "small");
        VBox card = new VBox(6, UI.label(title, "h3"), sub, content);
        card.getStyleClass().add("card");
        card.setMinHeight(330);
        VBox.setVgrow(content, Priority.ALWAYS);
        return card;
    }

    private static Node noData(String text) {
        VBox v = UI.emptyState(Icons.INFO, "No data yet", text);
        v.setMinHeight(240);
        return new StackPane(v);
    }

    // ------------------------------------------------------------------ charts

    private Node categoryPie(List<Object[]> rows) {
        if (rows.isEmpty()) return noData("Index a folder to see how its storage is distributed.");
        List<PieChart.Data> data = new ArrayList<>();
        long other = 0, otherFiles = 0;
        for (int i = 0; i < rows.size(); i++) {
            String name = Fmt.category((String) rows.get(i)[0]);
            long files = (Long) rows.get(i)[1];
            long bytes = (Long) rows.get(i)[2];
            if (i < 7) {
                data.add(slice(name, bytes, files + " files, " + Fmt.size(bytes)));
            } else {
                other += bytes;
                otherFiles += files;
            }
        }
        if (other > 0) data.add(slice("Other", other, otherFiles + " files, " + Fmt.size(other)));
        return pie(data);
    }

    private Node activityPie(List<Object[]> rows) {
        if (rows.isEmpty()) return noData("Activity is classified after the first scan.");
        List<PieChart.Data> data = new ArrayList<>();
        for (Object[] r : rows) {
            String name = label((String) r[0]);
            long files = (Long) r[1];
            data.add(slice(name, files, files + " files, " + Fmt.size((Long) r[2])));
        }
        return pie(data);
    }

    private static String label(String cls) {
        try {
            return ActivityClass.valueOf(cls).label();
        } catch (IllegalArgumentException | NullPointerException e) {
            return Fmt.title(cls);
        }
    }

    private static PieChart.Data slice(String name, double value, String tip) {
        PieChart.Data d = new PieChart.Data(name, Math.max(1, value));
        d.nodeProperty().addListener((o, a, n) -> {
            if (n != null) Tooltip.install(n, UI.tip(name + ": " + tip));
        });
        return d;
    }

    private static PieChart pie(List<PieChart.Data> data) {
        PieChart c = new PieChart(FXCollections.observableArrayList(data));
        c.setLabelsVisible(false);
        c.setAnimated(false);
        c.setLegendSide(javafx.geometry.Side.BOTTOM);
        c.setMinHeight(250);
        return c;
    }

    private Node rootBars(List<Object[]> rows) {
        if (rows.isEmpty()) return noData("Add a monitored folder to compare locations.");
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setTickLabelFormatter(sizeFormatter());
        BarChart<String, Number> chart = new BarChart<>(x, y);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setMinHeight(250);
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        for (Object[] r : rows) {
            String name = Fmt.middleEllipsis((String) r[0], 18);
            XYChart.Data<String, Number> d = new XYChart.Data<>(name, (Long) r[2]);
            tip(d, r[0] + ": " + r[1] + " files, " + Fmt.size((Long) r[2]));
            s.getData().add(d);
        }
        chart.getData().add(s);
        return chart;
    }

    private Node activeBars(List<Object[]> rows) {
        if (rows.isEmpty()) return noData("Activity is classified after the first scan.");
        long active = 0, inactive = 0, unknown = 0, protectedBytes = 0;
        for (Object[] r : rows) {
            long bytes = (Long) r[2];
            switch (String.valueOf(r[0])) {
                case "ACTIVE", "PROBABLY_ACTIVE" -> active += bytes;
                case "INACTIVE", "POTENTIALLY_INACTIVE" -> inactive += bytes;
                case "UNKNOWN" -> unknown += bytes;
                default -> protectedBytes += bytes;
            }
        }
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setTickLabelFormatter(sizeFormatter());
        BarChart<String, Number> chart = new BarChart<>(x, y);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setMinHeight(250);
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        String[] names = {"Active", "Inactive", "Unknown", "Pinned / excluded"};
        long[] vals = {active, inactive, unknown, protectedBytes};
        for (int i = 0; i < names.length; i++) {
            XYChart.Data<String, Number> d = new XYChart.Data<>(names[i], vals[i]);
            tip(d, names[i] + ": " + Fmt.size(vals[i]));
            s.getData().add(d);
        }
        chart.getData().add(s);
        return chart;
    }

    private Node savingsLine(List<long[]> rows) {
        if (rows.isEmpty()) return noData("No optimizations yet. Measured savings will be charted here.");
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setTickLabelFormatter(sizeFormatter());
        LineChart<String, Number> chart = new LineChart<>(x, y);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setCreateSymbols(rows.size() < 40);
        chart.setMinHeight(250);
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        long cumulative = 0;
        for (long[] r : rows) {
            cumulative += r[1];
            LocalDate date = Fmt.fromJulianDay(r[0]);
            XYChart.Data<String, Number> d = new XYChart.Data<>(date.toString(), cumulative);
            tip(d, date + ": net " + Fmt.signedSize(r[1]) + ", total " + Fmt.signedSize(cumulative));
            s.getData().add(d);
        }
        chart.getData().add(s);
        return chart;
    }

    private static <X, Y> void tip(XYChart.Data<X, Y> d, String text) {
        d.nodeProperty().addListener((o, a, n) -> {
            if (n != null) Tooltip.install(n, UI.tip(text));
        });
    }

    private static StringConverter<Number> sizeFormatter() {
        return new StringConverter<>() {
            @Override
            public String toString(Number n) {
                return n == null ? "" : Fmt.signedSize(n.longValue());
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        };
    }

    // ------------------------------------------------------------------ tables

    private Node effectivenessTable(List<Object[]> rows) {
        if (rows.isEmpty()) return noData("Effectiveness is measured after the first optimization.");
        TableView<Object[]> t = new TableView<>(FXCollections.observableArrayList(rows));
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        t.getColumns().add(UI.col("Method", 150, (Object[] r) -> Fmt.title((String) r[0])));
        t.getColumns().add(UI.numCol("Files", 60, (Object[] r) -> Long.toString((Long) r[1])));
        t.getColumns().add(UI.numCol("Original", 90, (Object[] r) -> Fmt.size((Long) r[2])));
        t.getColumns().add(UI.numCol("Saved (measured)", 120, (Object[] r) -> Fmt.size(Math.max(0, (Long) r[3] - (Long) r[4]))));
        t.getColumns().add(UI.numCol("Ratio", 70, (Object[] r) -> ((Long) r[3]) <= 0 ? Fmt.DASH
                : String.format("%.0f%%", 100.0 * (Long) r[4] / (Long) r[3])));
        t.setPrefHeight(250);
        return t;
    }

    private Node largestTable(List<Object[]> rows) {
        if (rows.isEmpty()) return noData("No recommendations yet. They appear after a scan finds idle files.");
        TableView<Object[]> t = new TableView<>(FXCollections.observableArrayList(rows));
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        t.getColumns().add(UI.col("File", 560, (Object[] r) -> (String) r[0]));
        t.getColumns().add(UI.numCol("Estimated savings", 140, (Object[] r) -> Fmt.estimate((Long) r[1])));
        t.getColumns().add(UI.col("Method", 200, (Object[] r) -> Fmt.title((String) r[2])));
        t.setPrefHeight(Math.min(380, 60 + rows.size() * 32));
        return t;
    }
}
