package io.github.madaai.idlestow.app.tray;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.Branding;
import io.github.madaai.idlestow.app.ui.Icons;
import io.github.madaai.idlestow.app.ui.Theme;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.util.Fmt;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/**
 * The tray icon's right-click menu: a small themed popup (logo, current status, a few actions). It is an
 * undecorated, transparent stage owned by an invisible utility stage so it gets no taskbar button. It closes when
 * it loses focus, on Escape, after an action, or after a few idle seconds if the pointer never enters it.
 */
final class TrayMenu {
    private static final double WIDTH = 260;

    private final AppContext ctx;
    private final Runnable openWindow;
    private final Runnable exit;
    private final PauseTransition idleClose = new PauseTransition(Duration.seconds(8));
    private Stage owner;
    private Stage stage;
    private boolean pointerInside;

    TrayMenu(AppContext ctx, Runnable openWindow, Runnable exit) {
        this.ctx = ctx;
        this.openWindow = openWindow;
        this.exit = exit;
        idleClose.setOnFinished(e -> {
            if (!pointerInside) hide();
        });
    }

    /** Shows the menu with its bottom-right corner at the given screen point (clamped to the screen). */
    void showAt(double x, double y) {
        hide();
        ensureStages();
        VBox content = build();
        Scene scene = new Scene(content);
        scene.setFill(Color.TRANSPARENT);
        Theme.register(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) hide();
        });
        stage.setScene(scene);
        content.applyCss();
        content.layout();
        double w = WIDTH + 24; // card plus shadow margin
        double h = content.prefHeight(w);
        Rectangle2D vb = Screen.getScreensForRectangle(x, y, 1, 1).stream().findFirst()
                .orElse(Screen.getPrimary()).getVisualBounds();
        double left = Math.max(vb.getMinX(), Math.min(x - w + 12, vb.getMaxX() - w));
        double top = y - h + 12 < vb.getMinY() ? y : y - h + 12;
        top = Math.max(vb.getMinY(), Math.min(top, vb.getMaxY() - h));
        stage.setX(left);
        stage.setY(top);
        stage.show();
        stage.toFront();
        stage.requestFocus();
        pointerInside = false;
        idleClose.playFromStart();
    }

    void hide() {
        idleClose.stop();
        if (stage != null && stage.isShowing()) stage.hide();
    }

    void dispose() {
        hide();
        if (owner != null) owner.close();
        owner = null;
        stage = null;
    }

    private void ensureStages() {
        if (stage != null) return;
        owner = new Stage(StageStyle.UTILITY);
        owner.setOpacity(0);
        owner.setWidth(1);
        owner.setHeight(1);
        owner.setX(-32000);
        owner.setY(-32000);
        owner.show();
        stage = new Stage(StageStyle.TRANSPARENT);
        stage.initOwner(owner);
        stage.setAlwaysOnTop(true);
        stage.setTitle("IdleStow menu");
        stage.focusedProperty().addListener((o, was, now) -> {
            if (!now) hide();
        });
    }

    // ------------------------------------------------------------------ content

    private VBox build() {
        IdleStowEngine e = ctx.engine();
        boolean paused = e.paused();

        Node logo = Branding.logo(30);
        Label name = UI.label("IdleStow", "tray-title");
        Label status = UI.label(statusText(e), "tray-status");
        HBox head = new HBox(10, logo, new VBox(1, name, status));
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("tray-head");

        VBox items = new VBox(2);
        items.getChildren().add(item("Open IdleStow", Icons.OVERVIEW, true, openWindow));
        if (paused) {
            items.getChildren().add(item("Resume", Icons.PLAY, false, e::resumeAll));
        } else {
            items.getChildren().add(item("Pause for 1 hour", Icons.PAUSE, false,
                    () -> e.pauseAll(java.time.Duration.ofHours(1))));
            items.getChildren().add(item("Pause until I resume", Icons.CLOCK, false, () -> e.pauseAll(null)));
        }
        Button scan = item("Scan now", Icons.REFRESH, false, () -> e.scan(null));
        scan.setDisable(e.scanStatus().running());
        items.getChildren().add(scan);
        items.getChildren().add(divider());
        items.getChildren().add(item("Exit IdleStow", Icons.X, false, exit));

        VBox card = new VBox(6, head, divider(), items);
        card.getStyleClass().add("tray-menu");
        card.setPrefWidth(WIDTH);
        card.setMaxWidth(WIDTH);

        VBox outer = new VBox(card);
        outer.setPadding(new Insets(12)); // room for the drop shadow
        outer.setStyle("-fx-background-color: transparent;");
        outer.setOnMouseEntered(ev -> pointerInside = true);
        outer.setOnMouseExited(ev -> {
            pointerInside = false;
            idleClose.playFromStart();
        });
        return outer;
    }

    private Button item(String text, Icons icon, boolean primary, Runnable action) {
        Button b = new Button(text);
        b.setGraphic(icon.node(16));
        b.getStyleClass().add("tray-item");
        if (primary) b.getStyleClass().add("tray-item-primary");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setFocusTraversable(true);
        b.setOnAction(ev -> {
            hide();
            action.run();
        });
        return b;
    }

    private static Region divider() {
        Region r = new Region();
        r.getStyleClass().add("tray-divider");
        VBox.setVgrow(r, Priority.NEVER);
        return r;
    }

    static String statusText(IdleStowEngine e) {
        if (e.paused()) {
            long until = e.pausedUntilMs();
            if (until > 0 && until != Long.MAX_VALUE) {
                String t = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                        .format(Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()));
                return "Paused until " + t;
            }
            return "Paused";
        }
        if (e.scanStatus().running()) return "Scanning your folders...";
        if (e.readOnly()) return "Read-only: nothing is changed";
        try {
            int n = e.roots().size();
            return n == 0 ? "No folders yet" : "Watching " + Fmt.plural(n, "folder");
        } catch (Exception ex) {
            return "Running";
        }
    }
}
