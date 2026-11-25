package io.github.madaai.idlestow.app.ui;

import io.github.madaai.idlestow.app.AppContext;
import io.github.madaai.idlestow.app.ui.components.UI;
import io.github.madaai.idlestow.app.util.Background;
import io.github.madaai.idlestow.app.util.Fmt;
import io.github.madaai.idlestow.core.db.JobRepository;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.model.Enums.JobState;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.List;

/** Bottom status bar: indexing, current job, autonomy, background, worker and read-only state. */
public final class StatusBar extends HBox {
    private record Snap(List<JobRepository.Job> active, long attention, boolean autonomyActive) { }

    private final AppContext ctx;
    private final Region scanDot = dot();
    private final Label scanLabel = item();
    private final ProgressBar scanProgress = new ProgressBar(-1);
    private final Region jobDot = dot();
    private final Label jobLabel = item();
    private final Region autoDot = dot();
    private final Label autoLabel = item();
    private final Region bgDot = dot();
    private final Label bgLabel = item();
    private final Region workerDot = dot();
    private final Label workerLabel = item();
    private final Label attentionLabel = item();
    private final Timeline poll;
    private boolean inFlight;
    private boolean started;
    private boolean active = true;
    private Snap last = new Snap(List.of(), 0, false);

    public StatusBar(AppContext ctx) {
        this.ctx = ctx;
        getStyleClass().add("status-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);
        scanProgress.setPrefWidth(80);
        scanProgress.setMaxHeight(6);
        scanProgress.setVisible(false);
        scanProgress.setManaged(false);
        attentionLabel.getStyleClass().add("warn-text");
        attentionLabel.setPadding(new Insets(0, 8, 0, 0));
        getChildren().addAll(group(scanDot, scanLabel, scanProgress), sep(), group(jobDot, jobLabel), sep(),
                group(autoDot, autoLabel), sep(), group(bgDot, bgLabel), UI.spacer(), attentionLabel,
                group(workerDot, workerLabel));
        if (ctx.engine().readOnly()) {
            Label ro = UI.badge("READ-ONLY", "warn");
            UI.tooltip(ro, "IdleStow was started with --read-only. It indexes and recommends but never changes files.");
            getChildren().add(ro);
        }
        poll = new Timeline(new KeyFrame(Duration.seconds(1.5), e -> refresh()));
        poll.setCycleCount(Animation.INDEFINITE);
    }

    public void start() {
        started = true;
        if (active) {
            refresh();
            poll.play();
        }
    }

    public void stop() {
        started = false;
        poll.stop();
    }

    /** The bar only polls while the window is actually showing (not while hidden to the tray). */
    public void setActive(boolean on) {
        if (active == on) return;
        active = on;
        if (!started) return;
        if (on) {
            refresh();
            poll.play();
        } else {
            poll.stop();
        }
    }

    private static Region dot() {
        Region r = new Region();
        r.setMinSize(9, 9);
        r.setPrefSize(9, 9);
        r.setMaxSize(9, 9);
        r.getStyleClass().add("status-dot-off");
        return r;
    }

    private static Label item() {
        Label l = new Label();
        l.getStyleClass().add("status-item");
        return l;
    }

    private static Region sep() {
        Region r = new Region();
        r.getStyleClass().add("status-sep");
        r.setMinHeight(14);
        return r;
    }

    private static HBox group(javafx.scene.Node... nodes) {
        HBox h = new HBox(6, nodes);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static void setDot(Region dot, String kind) {
        if (kind.equals(dot.getProperties().get("kind"))) return; // avoid a CSS pass when nothing changed
        dot.getProperties().put("kind", kind);
        dot.getStyleClass().removeIf(s -> s.startsWith("status-dot-"));
        dot.getStyleClass().add("status-dot-" + kind);
    }

    /** Cheap, safe to call often: reads in-memory state now, database state in the background. */
    public void refresh() {
        renderLive();
        if (inFlight) return;
        inFlight = true;
        Background.supply(() -> {
            IdleStowEngine e = ctx.engine();
            return new Snap(e.jobs().jobsInStates(List.of(JobState.QUEUED, JobState.PREFLIGHT, JobState.RUNNING,
                    JobState.VERIFYING, JobState.COMMITTING, JobState.PAUSED)),
                    e.recoveryRecords().countNeedingAttention(), e.autonomyActive());
        }).whenComplete((s, err) -> {
            inFlight = false;
            if (err == null) {
                last = s;
                renderLive();
            }
        });
    }

    private void renderLive() {
        IdleStowEngine e = ctx.engine();
        IdleStowEngine.ScanStatus st = e.scanStatus();
        if (st.running()) {
            setDot(scanDot, st.paused() ? "warn" : "busy");
            scanLabel.setText((st.paused() ? "Indexing paused: " : "Indexing ") + st.rootName() + " - "
                    + String.format("%,d", st.files()) + " files, " + Fmt.size(st.bytes()));
            scanProgress.setVisible(!st.paused());
            scanProgress.setManaged(!st.paused());
        } else {
            setDot(scanDot, "ok");
            scanLabel.setText("Index: " + (st.message() == null || st.message().isBlank() ? "idle" : st.message()));
            scanProgress.setVisible(false);
            scanProgress.setManaged(false);
        }

        List<JobRepository.Job> active = last.active();
        if (active.isEmpty()) {
            setDot(jobDot, "off");
            jobLabel.setText("No jobs running");
        } else {
            JobRepository.Job j = active.get(0);
            setDot(jobDot, j.state() == JobState.PAUSED ? "warn" : "busy");
            jobLabel.setText("Job #" + j.id() + " " + Fmt.title(j.kind().name()) + " " + j.doneItems() + "/"
                    + j.totalItems() + (j.state() == JobState.PAUSED ? " (paused)" : "")
                    + (active.size() > 1 ? "  +" + (active.size() - 1) + " more" : ""));
        }

        if (last.autonomyActive()) {
            setDot(autoDot, "ok");
            autoLabel.setText("Autonomy: on");
        } else {
            setDot(autoDot, "off");
            autoLabel.setText("Autonomy: off");
        }
        UI.tooltip(autoLabel, e.autonomyMessage());

        var s = e.settings();
        if (e.paused()) {
            setDot(bgDot, "warn");
            bgLabel.setText("Paused by you");
        } else if (s.backgroundEnabled()) {
            setDot(bgDot, "ok");
            long next = e.nextScanAtMs();
            bgLabel.setText("Background: on" + (next > 0 ? ", next scan " + Fmt.epochMs(next) : ""));
        } else {
            setDot(bgDot, "off");
            bgLabel.setText("Background: off");
        }

        if (!ctx.workerAvailable()) {
            setDot(workerDot, "warn");
            workerLabel.setText("Worker: unavailable");
        } else {
            setDot(workerDot, "ok");
            workerLabel.setText("Worker: " + e.worker().status());
        }
        UI.tooltip(workerLabel, ctx.workerNote());

        attentionLabel.setText(last.attention() > 0 ? last.attention() + " recovery item(s) need attention" : "");
    }
}
