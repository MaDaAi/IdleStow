package io.github.madaai.idlestow.app.ui;

import io.github.madaai.idlestow.app.ui.components.UI;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/** Themed modal dialogs. Must be called on the FX thread. */
public final class Dialogs {
    private Dialogs() { }

    private static Alert alert(Alert.AlertType type, Window owner, String title, String header, String text) {
        Alert a = new Alert(type);
        if (owner != null) a.initOwner(owner);
        a.setTitle(title);
        a.setHeaderText(header);
        a.setContentText(text);
        a.getDialogPane().setMinWidth(440);
        a.getDialogPane().setPrefWidth(520);
        Theme.style(a);
        return a;
    }

    public static void info(Window owner, String title, String header, String text) {
        alert(Alert.AlertType.INFORMATION, owner, title, header, text).showAndWait();
    }

    public static void error(Window owner, String title, String text) {
        alert(Alert.AlertType.ERROR, owner, title, title, text).showAndWait();
    }

    public static void warning(Window owner, String title, String text) {
        alert(Alert.AlertType.WARNING, owner, title, title, text).showAndWait();
    }

    /** Returns true when the user chose the confirm button. Cancel is the default button. */
    public static boolean confirm(Window owner, String title, String header, String text, String confirmLabel) {
        return confirm(owner, title, header, text, confirmLabel, null);
    }

    public static boolean confirm(Window owner, String title, String header, String text, String confirmLabel,
                                  Node extra) {
        Alert a = alert(Alert.AlertType.CONFIRMATION, owner, title, header, text);
        ButtonType ok = new ButtonType(confirmLabel, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        a.getButtonTypes().setAll(ok, cancel);
        if (extra != null) {
            VBox box = new VBox(10, UI.wrapped(text), extra);
            box.setPadding(new Insets(4, 0, 0, 0));
            a.getDialogPane().setContent(box);
        }
        // Safety: Enter must not confirm a consequential action.
        ((javafx.scene.control.Button) a.getDialogPane().lookupButton(ok)).setDefaultButton(false);
        ((javafx.scene.control.Button) a.getDialogPane().lookupButton(cancel)).setDefaultButton(true);
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == ok;
    }

    public static Optional<String> input(Window owner, String title, String header, String label, String initial) {
        TextInputDialog d = new TextInputDialog(initial == null ? "" : initial);
        if (owner != null) d.initOwner(owner);
        d.setTitle(title);
        d.setHeaderText(header);
        d.setContentText(label);
        Theme.style(d);
        return d.showAndWait();
    }

    /** Read-only monospace text with a copy button. */
    public static void textView(Window owner, String title, String text) {
        Dialog<Void> d = new Dialog<>();
        if (owner != null) d.initOwner(owner);
        d.setTitle(title);
        d.setResizable(true);
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("mono");
        area.setPrefSize(760, 460);
        VBox.setVgrow(area, Priority.ALWAYS);
        d.getDialogPane().setContent(area);
        ButtonType copy = new ButtonType("Copy", ButtonBar.ButtonData.LEFT);
        d.getDialogPane().getButtonTypes().addAll(copy, ButtonType.CLOSE);
        ((javafx.scene.control.Button) d.getDialogPane().lookupButton(copy)).addEventFilter(
                javafx.event.ActionEvent.ACTION, e -> {
                    ClipboardContent c = new ClipboardContent();
                    c.putString(text);
                    Clipboard.getSystemClipboard().setContent(c);
                    e.consume();
                });
        Theme.style(d);
        d.showAndWait();
    }

    public static Optional<Path> chooseDirectory(Window owner, String title) {
        DirectoryChooser c = new DirectoryChooser();
        c.setTitle(title);
        File f = c.showDialog(owner);
        return f == null ? Optional.empty() : Optional.of(f.toPath());
    }

    public static Optional<Path> saveFile(Window owner, String title, String initialName, String description,
                                          String... extensions) {
        FileChooser c = new FileChooser();
        c.setTitle(title);
        c.setInitialFileName(initialName);
        c.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, extensions));
        File f = c.showSaveDialog(owner);
        return f == null ? Optional.empty() : Optional.of(f.toPath());
    }
}
