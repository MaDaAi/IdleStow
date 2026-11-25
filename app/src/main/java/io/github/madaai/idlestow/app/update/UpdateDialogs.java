package io.github.madaai.idlestow.app.update;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.Dialogs;
import io.github.madaai.idlestow.app.ui.Theme;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.Fmt;
import io.github.madaai.idlestow.core.update.UpdateChecker;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.awt.Desktop;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** The few dialogs of the updater. All run on the FX thread. */
public final class UpdateDialogs {
    private UpdateDialogs() { }

    /** First run: asks once whether IdleStow may check GitHub for updates by itself. */
    public static void askAutoCheck(Window owner, UpdateService updates) {
        Dialog<ButtonType> d = new Dialog<>();
        if (owner != null) d.initOwner(owner);
        d.setTitle("Updates");
        d.setHeaderText("Check for updates automatically?");
        Label text = UI.wrapped("IdleStow can check once a day whether a newer version is available. That check is"
                + " the only time IdleStow uses the internet: it asks GitHub (where IdleStow is published) for the"
                + " latest version number. Nothing about you or your files is sent, though GitHub can see your IP"
                + " address, as with any website. If no update exists, nothing else happens. An update is only"
                + " downloaded when you choose to install it.");
        Label off = UI.wrapped("You can change this at any time in Settings > General > Updates, and check by hand"
                + " whenever you like.", "muted");
        VBox box = new VBox(10, text, off);
        box.setMaxWidth(520);
        box.setPadding(new Insets(4, 2, 4, 2));
        d.getDialogPane().setContent(box);
        ButtonType yes = new ButtonType("Yes, check automatically", ButtonBar.ButtonData.YES);
        ButtonType no = new ButtonType("No, I'll check myself", ButtonBar.ButtonData.NO);
        d.getDialogPane().getButtonTypes().setAll(no, yes);
        // No pre-selected answer: neither button is the default.
        ((Button) d.getDialogPane().lookupButton(yes)).setDefaultButton(false);
        ((Button) d.getDialogPane().lookupButton(no)).setDefaultButton(false);
        Theme.style(d);
        Optional<ButtonType> r = d.showAndWait();
        // Closing the dialog without choosing counts as "no" (the safe choice); it can be changed in Settings.
        updates.setAutoCheck(r.isPresent() && r.get() == yes);
    }

    /** Manual "Check for updates". */
    public static void checkNow(Window owner, AppContext ctx, UpdateService updates) {
        updates.check(true).whenComplete((r, err) -> {
            if (err != null) {
                Dialogs.error(owner, "Check for updates", "Could not reach GitHub: " + Background.message(err)
                        + "\n\nCheck your internet connection and try again later.");
            } else if (r.isEmpty()) {
                Dialogs.info(owner, "Check for updates", "IdleStow is up to date",
                        "You have the latest version (" + AppContext.version() + ").");
            } else {
                offer(owner, ctx, updates, r.get());
            }
        });
    }

    /** Shows what is new and offers to install it. */
    public static void offer(Window owner, AppContext ctx, UpdateService updates, UpdateChecker.Release r) {
        Dialog<ButtonType> d = new Dialog<>();
        if (owner != null) d.initOwner(owner);
        d.setTitle("Update available");
        d.setHeaderText("IdleStow " + r.version() + " is available (you have " + AppContext.version() + ")");
        TextArea notes = new TextArea(r.notes().isBlank() ? "No release notes." : r.notes());
        notes.setEditable(false);
        notes.setWrapText(true);
        notes.setPrefRowCount(12);
        Optional<String> unsupported = updates.installUnsupported();
        Label how = UI.wrapped(unsupported.orElse("Download size " + Fmt.size(r.zipSize()) + ". The download is"
                + " checked against its published checksum before anything changes. IdleStow then restarts into the"
                + " new version; your settings, history and recovery records are kept, and the previous version is"
                + " kept next to it as \"IdleStow.previous\"."), "muted");
        VBox box = new VBox(10, notes, how);
        box.setPrefWidth(600);
        d.getDialogPane().setContent(box);
        ButtonType install = new ButtonType(unsupported.isPresent() ? "Open download page" : "Install update",
                ButtonBar.ButtonData.OK_DONE);
        ButtonType skip = new ButtonType("Skip this version", ButtonBar.ButtonData.LEFT);
        ButtonType later = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
        d.getDialogPane().getButtonTypes().setAll(skip, later, install);
        Theme.style(d);
        Optional<ButtonType> res = d.showAndWait();
        if (res.isEmpty() || res.get() == later) return;
        if (res.get() == skip) {
            updates.skip(r);
            return;
        }
        if (unsupported.isPresent()) {
            openPage(owner, r.pageUrl());
        } else {
            install(owner, ctx, updates, r);
        }
    }

    private static void openPage(Window owner, String url) {
        Background.run(() -> Desktop.getDesktop().browse(URI.create(url))).whenComplete((v, err) -> {
            if (err != null) Dialogs.info(owner, "Download", "Open this page in your browser", url);
        });
    }

    private static void install(Window owner, AppContext ctx, UpdateService updates, UpdateChecker.Release r) {
        Background.supply(() -> ctx.engine().jobs().countActive()).whenComplete((active, err) -> {
            if (err == null && active != null && active > 0) {
                Dialogs.warning(owner, "Update", "IdleStow is still working (" + Fmt.plural(active, "job")
                        + "). Wait until it finishes, or pause it, then install the update.");
                return;
            }
            download(owner, ctx, updates, r);
        });
    }

    private static void download(Window owner, AppContext ctx, UpdateService updates, UpdateChecker.Release r) {
        AtomicBoolean cancelled = new AtomicBoolean();
        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(420);
        Label status = UI.label("Downloading IdleStow " + r.version() + "...", "muted");
        Dialog<ButtonType> d = new Dialog<>();
        if (owner != null) d.initOwner(owner);
        d.setTitle("Updating IdleStow");
        d.setHeaderText("Downloading and checking the update");
        d.getDialogPane().setContent(new VBox(10, status, bar));
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL);
        Theme.style(d);
        AtomicBoolean finished = new AtomicBoolean();
        // Any way of closing the dialog before the download finishes (Cancel, Esc, the window's X) cancels it.
        d.setOnHidden(e -> {
            if (!finished.get()) cancelled.set(true);
        });
        long size = Math.max(1, r.zipSize());
        updates.download(r, got -> Platform.runLater(() -> {
            bar.setProgress(Math.min(1.0, got / (double) size));
            status.setText("Downloading... " + Fmt.size(got) + " of " + Fmt.size(size));
        }), cancelled::get).whenComplete((staged, err) -> {
            boolean userCancelled = cancelled.get();
            finished.set(true);
            d.close();
            if (err != null) {
                if (userCancelled || err instanceof CancellationException
                        || err.getCause() instanceof CancellationException) return;
                Dialogs.error(owner, "Update failed", Background.message(err) + "\n\nIdleStow was not changed.");
                return;
            }
            boolean now = Dialogs.confirm(owner, "Update ready", "Restart IdleStow to finish the update?",
                    "IdleStow " + r.version() + " was downloaded and verified. IdleStow will close, swap in the new"
                            + " version and start again. This takes a few seconds.", "Restart now");
            if (!now) {
                Dialogs.info(owner, "Update", "The update was not installed",
                        "You can install it later from Settings > General > Updates.");
                updates.cleanUpLeftovers();
                return;
            }
            try {
                updates.launchInstaller(staged);
                if (ctx.lifecycle() != null) ctx.lifecycle().exit();
            } catch (java.io.IOException e) {
                Dialogs.error(owner, "Update failed", Background.message(e) + "\n\nIdleStow was not changed.");
                updates.cleanUpLeftovers();
            }
        });
        d.show();
    }
}
