package io.github.madaai.idlestow.app;

import io.github.madaai.idlestow.app.ui.Navigator;
import io.github.madaai.idlestow.app.util.UiPrefs;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;

import java.nio.file.Path;

/** Everything a view needs: the engine (the only backend façade), preferences, navigation and lifecycle hooks. */
public final class AppContext {

    /** Application level actions implemented by {@link IdleStowApp}. */
    public interface Lifecycle {
        void exit();

        void showWindow();

        boolean trayAvailable();

        void notifyUser(String title, String message, boolean error);
    }

    private final IdleStowEngine engine;
    private final LaunchArgs args;
    private final UiPrefs prefs;
    private final boolean workerAvailable;
    private final String workerNote;
    private volatile Navigator navigator;
    private volatile Lifecycle lifecycle;
    private volatile io.github.madaai.idlestow.app.update.UpdateService updates;

    public AppContext(IdleStowEngine engine, LaunchArgs args, UiPrefs prefs, boolean workerAvailable,
                      String workerNote) {
        this.engine = engine;
        this.args = args;
        this.prefs = prefs;
        this.workerAvailable = workerAvailable;
        this.workerNote = workerNote;
    }

    public IdleStowEngine engine() {
        return engine;
    }

    public LaunchArgs args() {
        return args;
    }

    public UiPrefs prefs() {
        return prefs;
    }

    public Path dataDir() {
        return engine.dataDir();
    }

    /** False when no Python worker could be started: indexing works, optimization and verification do not. */
    public boolean workerAvailable() {
        return workerAvailable;
    }

    public String workerNote() {
        return workerNote;
    }

    public Navigator nav() {
        return navigator;
    }

    public void setNavigator(Navigator n) {
        this.navigator = n;
    }

    public Lifecycle lifecycle() {
        return lifecycle;
    }

    public void setLifecycle(Lifecycle l) {
        this.lifecycle = l;
    }

    /** The updater; null in the smoke test. */
    public io.github.madaai.idlestow.app.update.UpdateService updates() {
        return updates;
    }

    public void setUpdates(io.github.madaai.idlestow.app.update.UpdateService u) {
        this.updates = u;
    }

    public static String version() {
        String v = AppContext.class.getPackage() == null ? null : AppContext.class.getPackage().getImplementationVersion();
        return v == null ? "0.1.2" : v;
    }
}
