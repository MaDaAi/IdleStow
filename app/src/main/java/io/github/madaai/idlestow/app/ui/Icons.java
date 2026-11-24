package io.github.madaai.idlestow.app.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/** Original vector icons drawn on a 24 x 24 grid (stroke based). No third-party icon sets are used. */
public enum Icons {
    OVERVIEW("M3 3h7v8H3z M14 3h7v5h-7z M14 12h7v9h-7z M3 15h7v6H3z"),
    EXPLORER("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"),
    RECOMMEND("M9 18h6 M10 21h4 M12 3a6 6 0 0 0-4 10.4c.8.8 1 1.6 1 2.6h6c0-1 .2-1.8 1-2.6A6 6 0 0 0 12 3z"),
    JOBS("M3 12h4l3-8 4 16 3-8h4"),
    HISTORY("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 7v5l3.5 2"),
    RECOVERY("M3 12a9 9 0 1 0 3-6.7 M3 4v5h5 M12 8v5l3 2"),
    SETTINGS("M4 6h9 M17 6h3 M4 12h3 M11 12h9 M4 18h11 M19 18h1 M15 4v4 M9 10v4 M17 16v4"),
    SEARCH("M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z M21 21l-4.6-4.6"),
    REFRESH("M20 11a8 8 0 1 0-2.3 6 M20 4v7h-7"),
    PAUSE("M8 5v14 M16 5v14"),
    PLAY("M7 4l13 8-13 8z"),
    STOP("M6 6l12 12 M18 6L6 18"),
    CHECK("M5 12.5l4.5 4.5L19 7.5"),
    PLUS("M12 5v14 M5 12h14"),
    SHIELD("M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"),
    INFO("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 11v6 M12 7.6v.4"),
    WARNING("M12 3.5l9.5 17h-19z M12 10v5 M12 17.6v.4"),
    FILE("M6 3h8l5 5v13H6z M14 3v5h5"),
    COMPRESS("M3 5h18v4H3z M5 9v10h14V9 M10 13h4"),
    COPY("M9 9h11v11H9z M5 15V4h11"),
    OPEN_EXTERNAL("M14 4h6v6 M20 4l-9 9 M18 14v6H4V6h6"),
    PIN("M12 17v5 M8 3h8l-1 7 3 4H6l3-4z"),
    DOWNLOAD("M12 4v11 M7 11l5 5 5-5 M4 20h16"),
    CHEVRON_RIGHT("M9 6l6 6-6 6"),
    CHEVRON_DOWN("M6 9l6 6 6-6"),
    FOLDER_OPEN("M3 19V6a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v2 M3 19l3-9h16l-3 9z"),
    LOCK("M6 11h12v10H6z M8 11V8a4 4 0 0 1 8 0v3"),
    EYE("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"),
    BELL("M6 16V11a6 6 0 0 1 12 0v5l2 2H4z M10 21h4"),
    CLOCK("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 7v5l3 2"),
    BOLT("M13 2L4 14h7l-1 8 9-12h-7z"),
    X("M6 6l12 12 M18 6L6 18"),
    LIST("M8 6h12 M8 12h12 M8 18h12 M4 6h.01 M4 12h.01 M4 18h.01"),
    TARGET("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z"),
    LOGO("M3.5 7.5L12 3l8.5 4.5L12 12z M3.5 12L12 16.5 20.5 12 M3.5 16.5L12 21l8.5-4.5");

    private final String path;

    Icons(String path) {
        this.path = path;
    }

    /** A new icon node of the given edge length. Colors come from the {@code .icon} style classes. */
    public Node node(double size) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.getStyleClass().add("icon");
        double s = size / 24.0;
        p.setScaleX(s);
        p.setScaleY(s);
        // The scaled path keeps its unscaled layout bounds; a fixed-size box keeps layout stable.
        StackPane box = new StackPane(new Group(p));
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        return box;
    }

    /** Same as {@link #node(double)} with an extra style class on the path, e.g. {@code icon-accent}. */
    public Node node(double size, String extraStyleClass) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.getStyleClass().addAll("icon", extraStyleClass);
        double s = size / 24.0;
        p.setScaleX(s);
        p.setScaleY(s);
        StackPane box = new StackPane(new Group(p));
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        return box;
    }
}
