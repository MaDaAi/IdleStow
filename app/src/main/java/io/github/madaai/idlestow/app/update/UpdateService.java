package io.github.madaai.idlestow.app.update;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.UiPrefs;
import io.github.madaai.idlestow.core.update.HttpsTransport;
import io.github.madaai.idlestow.core.update.UpdateChecker;
import io.github.madaai.idlestow.core.update.UpdatePackage;
import io.github.madaai.idlestow.core.update.UpdateTransport;
import io.github.madaai.idlestow.core.update.Version;
import io.github.madaai.idlestow.core.update.WindowsUpdateInstaller;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Update checks and installation for the app. Network use is limited to exactly this:
 * <ul>
 *   <li>with automatic checks on (the person's choice, asked once), at most one request per day to GitHub's
 *       "latest release" endpoint; nothing is downloaded unless a newer version exists and the person chooses to
 *       install it;</li>
 *   <li>"Check for updates" in Settings makes the same single request on demand;</li>
 *   <li>"Install" downloads that release's zip and checksum file.</li>
 * </ul>
 * No other part of IdleStow uses the network (enforced by NetworkAccessArchitectureTest).
 */
public final class UpdateService {
    private static final Logger LOG = LoggerFactory.getLogger(UpdateService.class);
    static final String KEY_AUTO = "update.autoCheck";
    static final String KEY_LAST = "update.lastCheckMs";
    static final String KEY_SKIP = "update.skipVersion";
    static final long DAY_MS = Duration.ofDays(1).toMillis();

    private final AppContext ctx;
    private final UpdateTransport transport;
    private final ObjectProperty<UpdateChecker.Release> available = new SimpleObjectProperty<>();
    private ScheduledExecutorService timer;
    private volatile boolean checking;

    public UpdateService(AppContext ctx) {
        this(ctx, new HttpsTransport(AppContext.version()));
    }

    UpdateService(AppContext ctx, UpdateTransport transport) {
        this.ctx = ctx;
        this.transport = transport;
    }

    public static Version currentVersion() {
        return Version.parse(AppContext.version()).orElse(new Version(0, 0, 0));
    }

    /** A newer release found by the last check (null when up to date or not checked). FX thread. */
    public ReadOnlyObjectProperty<UpdateChecker.Release> availableProperty() {
        return available;
    }

    // ------------------------------------------------------------------ the person's choice

    /** Null until the person has answered the first-run question. */
    public static Boolean autoCheck(UiPrefs prefs) {
        String v = prefs.getString(KEY_AUTO, null);
        return v == null ? null : Boolean.valueOf(v);
    }

    public void setAutoCheck(boolean on) {
        ctx.prefs().put(KEY_AUTO, Boolean.toString(on));
        ctx.prefs().save();
        if (on) startAutoChecks();
    }

    public long lastCheckMs() {
        return (long) ctx.prefs().getDouble(KEY_LAST, 0);
    }

    // ------------------------------------------------------------------ checking

    /** Starts the hourly timer that performs a check only when automatic checks are on and a day has passed. */
    public synchronized void startAutoChecks() {
        if (timer != null) return;
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idlestow-update-timer");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> Background.fx(this::maybeAutoCheck), 20, 3600, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (timer != null) timer.shutdownNow();
        timer = null;
    }

    private void maybeAutoCheck() {
        if (!Boolean.TRUE.equals(autoCheck(ctx.prefs()))) return;
        if (System.currentTimeMillis() - lastCheckMs() < DAY_MS) return;
        check(false);
    }

    /**
     * One request to GitHub. Completes on the FX thread with the newer release, or empty when up to date.
     * Automatic checks stay silent about versions the person chose to skip; manual checks always report.
     */
    public CompletableFuture<Optional<UpdateChecker.Release>> check(boolean manual) {
        if (checking) return CompletableFuture.completedFuture(Optional.ofNullable(available.get()));
        checking = true;
        Version current = currentVersion();
        return Background.supply(() -> new UpdateChecker(transport).check(current)).handle((r, err) -> {
            checking = false;
            ctx.prefs().put(KEY_LAST, (double) System.currentTimeMillis());
            ctx.prefs().save();
            if (err != null) {
                LOG.info("Update check failed: {}", Background.message(err));
                if (manual) throw new java.util.concurrent.CompletionException(err);
                return Optional.<UpdateChecker.Release>empty();
            }
            Optional<UpdateChecker.Release> found = r;
            if (found.isPresent() && !manual
                    && found.get().version().toString().equals(ctx.prefs().getString(KEY_SKIP, ""))) {
                found = Optional.empty();
            }
            available.set(found.orElse(null));
            if (found.isPresent()) LOG.info("Update available: {}", found.get().version());
            return found;
        });
    }

    public void skip(UpdateChecker.Release r) {
        ctx.prefs().put(KEY_SKIP, r.version().toString());
        ctx.prefs().save();
        available.set(null);
    }

    // ------------------------------------------------------------------ installing

    /** Why this copy cannot install updates itself, or empty when it can. */
    public Optional<String> installUnsupported() {
        if (ctx.args().readOnly()) return Optional.of("IdleStow was started in read-only mode, so it does not"
                + " change its own files either. Download the new version from GitHub instead.");
        return WindowsUpdateInstaller.unsupportedReason();
    }

    Path workDir() {
        return ctx.dataDir().resolve("updates");
    }

    /** Downloads and verifies the release into a staging folder next to the app. Runs in the background. */
    public CompletableFuture<UpdatePackage.Staged> download(UpdateChecker.Release r, LongConsumer progress,
                                                            BooleanSupplier cancelled) {
        return Background.supply(() -> {
            Path app = WindowsUpdateInstaller.appDir().orElseThrow(() -> new IllegalStateException(
                    installUnsupported().orElse("Updates cannot be installed here.")));
            return new UpdatePackage(transport).prepare(r, workDir(), WindowsUpdateInstaller.stagingDir(app),
                    progress, cancelled);
        });
    }

    /** Hands over to the swap script; the caller exits IdleStow right after. */
    public void launchInstaller(UpdatePackage.Staged staged) throws java.io.IOException {
        Path app = WindowsUpdateInstaller.appDir().orElseThrow(() -> new java.io.IOException("No application folder"));
        Path dataDir = ctx.args().dataDir() == null ? null : ctx.dataDir();
        WindowsUpdateInstaller.launch(staged, app, workDir(), dataDir);
        // The new version starts fresh: no need to contact GitHub again right away.
        ctx.prefs().put(KEY_LAST, (double) System.currentTimeMillis());
        ctx.prefs().save();
    }

    /** Removes a staging folder left behind by a cancelled or failed update (only IdleStow-created ones). */
    public void cleanUpLeftovers() {
        Background.run(() -> {
            Optional<Path> app = WindowsUpdateInstaller.appDir();
            if (app.isPresent()) UpdatePackage.clearStaging(WindowsUpdateInstaller.stagingDir(app.get()));
        }).exceptionally(t -> {
            LOG.debug("Update clean-up skipped: {}", Background.message(t));
            return null;
        });
    }
}
