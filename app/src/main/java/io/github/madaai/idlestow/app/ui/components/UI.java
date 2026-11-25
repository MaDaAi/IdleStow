package io.github.madaai.idlestow.app.ui.components;

import io.github.madaai.idlestow.app.ui.Icons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.function.Function;

/** Small factory methods that keep the view code short and the look consistent. */
public final class UI {
    private UI() { }

    public static Label label(String text, String... styles) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styles);
        return l;
    }

    public static Label wrapped(String text, String... styles) {
        Label l = label(text, styles);
        l.setWrapText(true);
        return l;
    }

    public static Label muted(String text) {
        return wrapped(text, "muted");
    }

    public static Button button(String text, Icons icon, String... styles) {
        Button b = new Button(text);
        if (icon != null) b.setGraphic(icon.node(16));
        b.getStyleClass().addAll(styles);
        return b;
    }

    public static Button button(String text, Icons icon, Runnable action, String... styles) {
        Button b = button(text, icon, styles);
        b.setOnAction(e -> action.run());
        return b;
    }

    public static Button iconButton(Icons icon, String tooltip, Runnable action) {
        Button b = new Button();
        b.setGraphic(icon.node(18));
        b.getStyleClass().add("flat");
        b.setTooltip(tip(tooltip));
        b.setAccessibleText(tooltip);
        b.setOnAction(e -> action.run());
        return b;
    }

    public static Tooltip tip(String text) {
        Tooltip t = new Tooltip(text);
        t.setShowDelay(Duration.millis(350));
        t.setWrapText(true);
        t.setMaxWidth(420);
        return t;
    }

    public static void tooltip(Control c, String text) {
        Tooltip old = c.getTooltip();
        if (text == null || text.isBlank()) {
            if (old != null) c.setTooltip(null);
        } else if (old == null || !text.equals(old.getText())) {
            c.setTooltip(tip(text)); // unchanged text keeps the existing tooltip (this is called from polling code)
        }
    }

    public static HBox hbox(double spacing, Node... children) {
        HBox h = new HBox(spacing, children);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    public static VBox vbox(double spacing, Node... children) {
        return new VBox(spacing, children);
    }

    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    public static Region divider() {
        Region r = new Region();
        r.getStyleClass().add("divider");
        return r;
    }

    /** kind: info | ok | warn | error | neutral | estimate | measured */
    public static Label badge(String text, String kind) {
        Label l = new Label(text);
        l.getStyleClass().addAll("badge", "badge-" + kind);
        return l;
    }

    public static HBox banner(Icons icon, String text, String kind, Node... actions) {
        Label msg = wrapped(text);
        msg.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(msg, Priority.ALWAYS);
        String iconStyle = switch (kind) {
            case "warn" -> "icon-warning";
            case "error" -> "icon-error";
            case "ok" -> "icon-success";
            default -> "icon-accent";
        };
        HBox b = new HBox(10, icon.node(18, iconStyle), msg);
        b.setAlignment(Pos.CENTER_LEFT);
        b.getChildren().addAll(actions);
        b.getStyleClass().addAll("banner", "banner-" + kind);
        return b;
    }

    public static VBox card(String title, Node content) {
        VBox c = new VBox(10);
        c.getStyleClass().add("card");
        if (title != null) c.getChildren().add(label(title, "h3"));
        c.getChildren().add(content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return c;
    }

    public static VBox emptyState(Icons icon, String title, String text, Node... actions) {
        VBox v = new VBox(10);
        v.setAlignment(Pos.CENTER);
        v.setPadding(new Insets(28));
        v.getStyleClass().add("empty-state");
        Node ic = icon.node(44, "icon-accent");
        Label t = label(title, "empty-title");
        t.setTextAlignment(TextAlignment.CENTER);
        Label d = wrapped(text, "empty-text");
        d.setMaxWidth(460);
        d.setTextAlignment(TextAlignment.CENTER);
        v.getChildren().addAll(ic, t, d);
        if (actions.length > 0) {
            HBox row = new HBox(10, actions);
            row.setAlignment(Pos.CENTER);
            v.getChildren().add(row);
        }
        return v;
    }

    /** A dashboard tile: title, big value, sub text, and an optional measured/estimate badge. */
    public static VBox kpi(String title, Label value, String sub, String badgeText, String badgeKind) {
        HBox top = new HBox(8, label(title.toUpperCase(), "kpi-title"));
        top.setAlignment(Pos.CENTER_LEFT);
        if (badgeText != null) {
            top.getChildren().addAll(spacer(), badge(badgeText, badgeKind));
        }
        value.getStyleClass().add("kpi-value");
        VBox v = new VBox(4, top, value);
        if (sub != null) v.getChildren().add(wrapped(sub, "kpi-sub"));
        v.getStyleClass().add("card");
        v.setMinWidth(210);
        return v;
    }

    public static <T> TableColumn<T, String> col(String title, double width, Function<T, String> f) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new javafx.beans.property.ReadOnlyStringWrapper(safe(f, cd.getValue())));
        return c;
    }

    /** Right aligned (numeric) column. */
    public static <T> TableColumn<T, String> numCol(String title, double width, Function<T, String> f) {
        TableColumn<T, String> c = col(title, width, f);
        c.getStyleClass().add("right");
        c.setStyle("-fx-alignment: CENTER-RIGHT;");
        return c;
    }

    private static <T> String safe(Function<T, String> f, T v) {
        try {
            String s = f.apply(v);
            return s == null ? "" : s;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
