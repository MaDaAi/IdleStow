package io.github.madaai.idlestow.app.ui.views;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.Icons;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.Coalescer;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Common behaviour of all main views: a loading state, an error state with retry, and coalesced refreshes
 * driven by engine events. Loading itself ({@link #load()}) starts background work and completes on the FX thread.
 */
public abstract class BaseView {
    protected final AppContext ctx;
    private final StackPane root = new StackPane();
    private final StackPane loadingLayer = new StackPane();
    private final HBox errorLayer = new HBox();
    private final Coalescer coalescer;
    private volatile boolean visible;
    private volatile boolean dirty = true;
    private boolean loading;
    private boolean reloadRequested;
    private boolean loadedOnce;

    protected BaseView(AppContext ctx) {
        this.ctx = ctx;
        this.coalescer = new Coalescer(refreshIntervalMs(), Platform::runLater, this::refresh);
        ProgressIndicator pi = new ProgressIndicator();
        pi.setMaxSize(42, 42);
        loadingLayer.getChildren().add(pi);
        loadingLayer.getStyleClass().add("loading-overlay");
        loadingLayer.setVisible(false);
        loadingLayer.setPickOnBounds(false);
        errorLayer.setVisible(false);
        errorLayer.setManaged(false);
        StackPane.setAlignment(errorLayer, Pos.TOP_CENTER);
        errorLayer.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        errorLayer.setAlignment(Pos.TOP_CENTER);
        root.getChildren().addAll(loadingLayer, errorLayer);
    }

    /** Title shown in the toolbar. */
    public abstract String title();

    /** Starts (background) loading; the returned future completes on the FX thread. */
    protected abstract CompletableFuture<Void> load();

    /** Engine events this view cares about. */
    protected Set<IdleStowEngine.Event> events() {
        return Set.of(IdleStowEngine.Event.ANALYSIS, IdleStowEngine.Event.ROOTS, IdleStowEngine.Event.JOBS);
    }

    protected long refreshIntervalMs() {
        return 600;
    }

    /** Optional contextual controls placed in the top toolbar while this view is active. */
    public Node toolbarContent() {
        return null;
    }

    /** Ctrl+F. Returns false when the view has no search box. */
    public boolean focusSearch() {
        return false;
    }

    /** Sets the main content (called once by the subclass constructor). */
    protected final void setContent(Node content) {
        root.getChildren().add(0, content);
    }

    public final Node node() {
        return root;
    }

    // ------------------------------------------------------------------ lifecycle

    public void onShown() {
        visible = true;
        if (dirty || !loadedOnce) refresh();
    }

    public void onHidden() {
        visible = false;
    }

    public final boolean isVisibleView() {
        return visible;
    }

    /** May be called from any thread. */
    public final void onEngineEvent(IdleStowEngine.Event e) {
        if (!events().contains(e) || !relevant(e)) return;
        if (visible) coalescer.request();
        else dirty = true;
    }

    /** Subclasses can filter noisy events (for example scan progress ticks). */
    protected boolean relevant(IdleStowEngine.Event e) {
        return true;
    }

    public final CompletableFuture<Void> refresh() {
        if (loading) {
            reloadRequested = true;
            return CompletableFuture.completedFuture(null);
        }
        loading = true;
        dirty = false;
        if (!loadedOnce) loadingLayer.setVisible(true);
        CompletableFuture<Void> f;
        try {
            f = load();
        } catch (RuntimeException e) {
            f = CompletableFuture.failedFuture(e);
        }
        return f.whenComplete((v, t) -> {
            loading = false;
            loadingLayer.setVisible(false);
            if (t != null) showError(t);
            else clearError();
            loadedOnce = true;
            if (reloadRequested) {
                reloadRequested = false;
                refresh();
            }
        });
    }

    private void showError(Throwable t) {
        String msg = Background.message(t instanceof CompletionException && t.getCause() != null ? t.getCause() : t);
        Button retry = UI.button("Retry", Icons.REFRESH, this::refresh);
        HBox b = UI.banner(Icons.WARNING, "Could not load " + title() + ": " + msg, "error", retry);
        b.setMaxWidth(760);
        errorLayer.getChildren().setAll(b);
        errorLayer.setManaged(true);
        errorLayer.setVisible(true);
        errorLayer.setPickOnBounds(false);
        StackPane.setMargin(errorLayer, new javafx.geometry.Insets(12));
    }

    private void clearError() {
        errorLayer.setVisible(false);
        errorLayer.setManaged(false);
        errorLayer.getChildren().clear();
    }

    // ------------------------------------------------------------------ helpers for subclasses

    protected final void toast(String message) {
        if (ctx.nav() != null) ctx.nav().toast(message);
    }

    protected final javafx.stage.Window owner() {
        return ctx.nav() == null ? null : ctx.nav().window();
    }

    protected static VBox page(Node... children) {
        VBox v = new VBox(16, children);
        v.setPadding(new javafx.geometry.Insets(18));
        return v;
    }
}
