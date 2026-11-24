package io.github.madaai.idlestow.app.ui;

import javafx.scene.Scene;
import javafx.scene.control.Dialog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Runtime switchable dark / light theme. All colors are CSS tokens (see dark.css, light.css, base.css). */
public final class Theme {

    public enum Mode {
        DARK("dark"), LIGHT("light");
        private final String setting;

        Mode(String setting) {
            this.setting = setting;
        }

        public String setting() {
            return setting;
        }

        public static Mode of(String setting) {
            return setting != null && setting.toLowerCase(Locale.ROOT).equals("light") ? LIGHT : DARK;
        }
    }

    private static final List<WeakReference<Scene>> SCENES = new ArrayList<>();
    private static volatile Mode mode = Mode.DARK;

    private Theme() { }

    public static Mode mode() {
        return mode;
    }

    public static synchronized List<String> sheets(Mode m) {
        String palette = (m == Mode.LIGHT ? "light.css" : "dark.css");
        return List.of(url(palette), url("base.css"));
    }

    private static String url(String name) {
        var u = Theme.class.getResource(name);
        if (u == null) throw new IllegalStateException("Missing stylesheet " + name);
        return u.toExternalForm();
    }

    /** Applies the current theme to a scene and keeps it updated when the theme changes. */
    public static synchronized void register(Scene scene) {
        scene.getStylesheets().setAll(sheets(mode));
        SCENES.removeIf(r -> r.get() == null);
        SCENES.add(new WeakReference<>(scene));
    }

    public static synchronized void set(Mode m) {
        mode = m;
        for (WeakReference<Scene> r : SCENES) {
            Scene s = r.get();
            if (s != null) s.getStylesheets().setAll(sheets(m));
        }
    }

    /** Styles a dialog (its own scene) with the current theme. */
    public static void style(Dialog<?> dialog) {
        dialog.getDialogPane().getStylesheets().setAll(sheets(mode));
    }
}
