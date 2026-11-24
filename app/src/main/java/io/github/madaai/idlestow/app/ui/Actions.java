package io.github.madaai.idlestow.app.ui;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.core.db.RecoveryRepository;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Window;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** User actions shared by several views. Every engine call runs in the background. */
public final class Actions {
    private Actions() { }

    /** Choose a folder, authorize it, and optionally start the first (read-only) scan. */
    public static void addFolder(AppContext ctx, Window owner, boolean scanAfter, Consumer<MonitoredRoot> after) {
        Optional<Path> dir = Dialogs.chooseDirectory(owner, "Choose a folder IdleStow may index");
        if (dir.isEmpty()) return;
        addFolder(ctx, owner, dir.get(), scanAfter, after);
    }

    public static void addFolder(AppContext ctx, Window owner, Path dir, boolean scanAfter,
                                 Consumer<MonitoredRoot> after) {
        Background.supply(() -> ctx.engine().addRoot(dir)).whenComplete((root, err) -> {
            if (err != null) {
                Dialogs.error(owner, "This folder cannot be added", Background.message(err));
                return;
            }
            if (scanAfter) ctx.engine().scan(root.id());
            if (ctx.nav() != null) ctx.nav().toast("Added " + root.path() + (scanAfter ? " - indexing started" : ""));
            if (after != null) after.accept(root);
        });
    }

    public static void copyText(String text) {
        ClipboardContent c = new ClipboardContent();
        c.putString(text);
        Clipboard.getSystemClipboard().setContent(c);
    }

    /** Opens a file or folder with the default application (off the FX thread). */
    public static void openExternal(Window owner, Path path, Runnable afterOpened) {
        Background.run(() -> {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new IllegalStateException("Opening files is not supported on this system");
            }
            if (!Files.exists(path)) throw new IllegalStateException("The file no longer exists: " + path);
            Desktop.getDesktop().open(path.toFile());
            if (afterOpened != null) afterOpened.run();
        }).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner, "Cannot open", Background.message(err));
        });
    }

    /** Shows a file in its folder (selected where the OS supports it). */
    public static void reveal(Window owner, Path path) {
        Background.run(() -> {
            if (!Desktop.isDesktopSupported()) throw new IllegalStateException("Not supported on this system");
            Desktop d = Desktop.getDesktop();
            if (Files.exists(path) && d.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                d.browseFileDirectory(path.toFile());
            } else {
                Path dir = Files.isDirectory(path) ? path : path.getParent();
                if (dir == null || !Files.isDirectory(dir)) throw new IllegalStateException("Folder not found");
                d.open(dir.toFile());
            }
        }).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner, "Cannot open folder", Background.message(err));
        });
    }

    public static void openFolder(Window owner, Path dir) {
        Background.run(() -> {
            Files.createDirectories(dir);
            if (!Desktop.isDesktopSupported()) throw new IllegalStateException("Not supported on this system");
            Desktop.getDesktop().open(dir.toFile());
        }).whenComplete((v, err) -> {
            if (err != null) Dialogs.error(owner, "Cannot open folder", Background.message(err));
        });
    }

    /** Tells the user a job started and offers a way to follow it. */
    public static void jobStarted(AppContext ctx, long jobId, String what) {
        if (ctx.nav() != null) ctx.nav().toast(what + " started as job #" + jobId + " - see Jobs for progress");
    }

    /**
     * Restore flow for an archive record: in place, or into another folder (optionally with a new name).
     * A conflict (existing file) is reported in the job message.
     */
    public static void restore(AppContext ctx, Window owner, RecoveryRepository.Record rec) {
        String name = Path.of(rec.originalPath()).getFileName().toString();
        ButtonType inPlace = new ButtonType("Restore in place", ButtonBar.ButtonData.OK_DONE);
        ButtonType elsewhere = new ButtonType("Restore to another folder...", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert a = new Alert(Alert.AlertType.NONE);
        if (owner != null) a.initOwner(owner);
        a.setTitle("Restore " + name);
        a.setHeaderText("Restore " + name);
        a.setContentText("The original file is rebuilt from the verified archive and its SHA-256 is checked."
                + " An existing file is never overwritten. The archive is kept until you remove it.\n\nOriginal location:\n"
                + rec.originalPath());
        a.getButtonTypes().setAll(inPlace, elsewhere, cancel);
        Theme.style(a);
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() == cancel) return;
        Path dest = null;
        if (r.get() == elsewhere) {
            Optional<Path> dir = Dialogs.chooseDirectory(owner, "Restore into which folder?");
            if (dir.isEmpty()) return;
            Optional<String> newName = Dialogs.input(owner, "Restore " + name, "File name in the new folder",
                    "Name:", name);
            if (newName.isEmpty()) return;
            String n = newName.get().isBlank() ? name : newName.get().trim();
            if (n.contains("/") || n.contains("\\") || n.equals("..") || n.equals(".")) {
                Dialogs.error(owner, "Invalid name", "Enter a file name without folder separators.");
                return;
            }
            dest = dir.get().resolve(n);
        }
        Path destination = dest;
        Background.supply(() -> ctx.engine().restore(rec.id(), destination)).whenComplete((id, err) -> {
            if (err != null) Dialogs.error(owner, "Cannot restore", Background.message(err));
            else jobStarted(ctx, id, "Restore");
        });
    }

    /** The command registered for "start at login": this executable plus {@code --background}. */
    public static List<String> startupCommand(AppContext ctx) {
        List<String> cmd = new ArrayList<>();
        String packaged = System.getProperty("jpackage.app-path");
        if (packaged != null && !packaged.isBlank()) {
            cmd.add(packaged);
        } else {
            String exe = ProcessHandle.current().info().command().orElse(null);
            if (exe == null) {
                exe = Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name", "").toLowerCase().startsWith("windows") ? "javaw.exe" : "java")
                        .toString();
            }
            cmd.add(exe);
            String cp = System.getProperty("java.class.path", "");
            boolean runningJar = !cp.isBlank();
            if (runningJar) {
                cmd.add("-cp");
                cmd.add(cp);
                cmd.add("io.github.madaai.idlestow.app.Launcher");
            }
        }
        cmd.add("--background");
        if (ctx.engine().readOnly()) cmd.add("--read-only"); // keep the safety mode the user chose
        if (ctx.args().dataDir() != null) {
            cmd.add("--data-dir");
            cmd.add(ctx.args().dataDir().toString());
        }
        return cmd;
    }
}
