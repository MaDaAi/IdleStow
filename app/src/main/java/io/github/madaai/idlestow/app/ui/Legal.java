package io.github.madaai.idlestow.app.ui;

import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.util.UiPrefs;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/**
 * Terms of Use acceptance and the bundled legal texts (TERMS.md, PRIVACY.md, LICENSE, THIRD-PARTY-NOTICES.md are
 * copied from the repository root into the application at build time, see app/pom.xml).
 *
 * <p>Acceptance is recorded per data folder in ui-state.json as the accepted terms version and time. When the terms
 * version changes, people are asked again.
 */
public final class Legal {
    /** Must match the "Version" line in TERMS.md. Change both together whenever the terms change materially. */
    public static final String TERMS_VERSION = "2026-11-25";

    static final String KEY_VERSION = "legal.termsAcceptedVersion";
    static final String KEY_AT = "legal.termsAcceptedAt";

    private Legal() { }

    public static boolean accepted(UiPrefs prefs) {
        return TERMS_VERSION.equals(prefs.getString(KEY_VERSION, null));
    }

    public static void recordAcceptance(UiPrefs prefs) {
        prefs.put(KEY_VERSION, TERMS_VERSION);
        prefs.put(KEY_AT, Instant.now().toString());
        prefs.save();
    }

    /** Bundled document text, or a short pointer to the repository if it is missing from this build. */
    public static String text(String resource) {
        try (InputStream in = Legal.class.getResourceAsStream("/" + resource)) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // fall through
        }
        return resource + " is not included in this build. See https://github.com/MaDaAi/IdleStow/blob/main/"
                + resource;
    }

    public static void showDocument(Window owner, String title, String resource) {
        Dialogs.textView(owner, title, text(resource));
    }

    /** Links to the four documents, for Settings and the acceptance dialog. */
    public static Node links(Window owner) {
        HBox h = new HBox(4,
                link(owner, "Terms of Use", "TERMS.md"),
                link(owner, "Privacy notice", "PRIVACY.md"),
                link(owner, "License", "LICENSE"),
                link(owner, "Third-party notices", "THIRD-PARTY-NOTICES.md"));
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return h;
    }

    private static Hyperlink link(Window owner, String text, String resource) {
        Hyperlink l = new Hyperlink(text);
        l.setUnderline(true);
        l.setOnAction(e -> showDocument(owner, text, resource));
        return l;
    }

    /**
     * Shows the first-run agreement. Returns true when the person agreed (and records it); false means they chose
     * not to, and the caller must exit without doing anything.
     */
    public static boolean requestAcceptance(Window owner, UiPrefs prefs) {
        if (accepted(prefs)) return true;
        Dialog<ButtonType> d = new Dialog<>();
        if (owner != null) d.initOwner(owner);
        d.setTitle("Welcome to IdleStow");
        d.setHeaderText("Before you start");
        boolean update = prefs.getString(KEY_VERSION, null) != null;
        VBox box = new VBox(10);
        box.setPadding(new Insets(4, 2, 4, 2));
        box.setMaxWidth(560);
        if (update) {
            box.getChildren().add(UI.banner(Icons.INFO, "The Terms of Use have changed since you last agreed."
                    + " Please read and agree to continue.", "info"));
        }
        box.getChildren().addAll(
                point(Icons.SHIELD, "Free software, provided as is",
                        "IdleStow is free and open source (Apache License 2.0). It comes with no warranty, to the"
                                + " extent the law allows."),
                point(Icons.WARNING, "Keep your own backups",
                        "IdleStow changes how files are stored, and every change is checked and can be undone. Still,"
                                + " no software is perfect. Compression is not a backup: back up anything important"
                                + " before you use IdleStow."),
                point(Icons.LOCK, "Your data stays on your computer",
                        "No accounts, no telemetry, and your files never leave your computer. The only internet use is"
                                + " checking GitHub for updates, and only if you allow it. See the Privacy notice."),
                point(Icons.EYE, "You choose what it touches",
                        "IdleStow only reads the folders you pick and only changes files after you agree (or after"
                                + " you turn on fully autonomous mode yourself)."));
        Label read = UI.wrapped("Please read the full documents:", "muted");
        CheckBox agree = new CheckBox("I have read and agree to the Terms of Use, and I understand the Privacy"
                + " notice.");
        agree.setWrapText(true);
        box.getChildren().addAll(read, links(owner), agree);
        d.getDialogPane().setContent(box);
        ButtonType ok = new ButtonType("Agree and continue", ButtonBar.ButtonData.OK_DONE);
        ButtonType no = new ButtonType("Exit", ButtonBar.ButtonData.CANCEL_CLOSE);
        d.getDialogPane().getButtonTypes().setAll(no, ok);
        Button okButton = (Button) d.getDialogPane().lookupButton(ok);
        okButton.disableProperty().bind(agree.selectedProperty().not());
        d.getDialogPane().setPrefWidth(620);
        Theme.style(d);
        Optional<ButtonType> r = d.showAndWait();
        if (r.isPresent() && r.get() == ok && agree.isSelected()) {
            recordAcceptance(prefs);
            return true;
        }
        return false;
    }

    private static Node point(Icons icon, String title, String text) {
        VBox t = new VBox(2, UI.label(title, "h3"), UI.wrapped(text, "muted"));
        HBox h = new HBox(12, icon.node(22, "icon-accent"), t);
        h.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        return h;
    }
}
