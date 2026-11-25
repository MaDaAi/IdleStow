package io.github.madaai.idlestow.core.activity;

import io.github.madaai.idlestow.core.model.ActivityAssessment;
import io.github.madaai.idlestow.core.model.Enums.ActivityClass;
import io.github.madaai.idlestow.core.model.Enums.Confidence;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.platform.VolumeInfo.AccessTracking;
import io.github.madaai.idlestow.core.policy.PolicyEngine.Protection;
import io.github.madaai.idlestow.core.policy.PolicyEngine.Restriction;
import io.github.madaai.idlestow.core.util.Times;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, explainable activity rules. No machine learning; every classification lists its evidence.
 *
 * <p>Key safety properties (see docs/architecture/activity-model.md):
 * <ul>
 *   <li>Any trustworthy evidence of recent activity (modification, creation/copy, opening through
 *       IdleStow, reliable last-access, user declaration) makes the file active.</li>
 *   <li>An old modification time alone is never treated as proof of inactivity. Without reliable
 *       last-access tracking the file is {@link ActivityClass#UNKNOWN} unless the user designated the
 *       folder as archival storage or explicitly confirmed inactivity.</li>
 *   <li>Newly indexed files must pass an observation period before they can reach HIGH confidence.</li>
 *   <li>Timestamps in the future are treated as inconsistent → UNKNOWN.</li>
 * </ul>
 */
public final class ActivityClassifier {

    private static final long DAY = 86_400_000L;
    private static final long FUTURE_TOLERANCE = DAY;

    /** All inputs needed to classify one file. */
    public record Input(
            IndexedItem item,
            Protection protection,
            AccessTracking accessTracking,
            int thresholdDays,
            boolean archival,
            int observationDays,
            long openedViaIdleStowMs,
            long userDeclaredActiveMs,
            long userConfirmedInactiveMs,
            long nowMs) { }

    public ActivityAssessment classify(Input in) {
        IndexedItem it = in.item();
        List<String> reasons = new ArrayList<>();
        long now = in.nowMs();

        if (in.protection().restriction() == Restriction.PINNED) {
            return new ActivityAssessment(ActivityClass.PINNED, Confidence.HIGH, 0, -1,
                    List.of(in.protection().reason() + "; it will never be optimized automatically."));
        }
        if (in.protection().blocked()) {
            return new ActivityAssessment(ActivityClass.EXCLUDED, Confidence.HIGH, 0, -1,
                    List.of(in.protection().reason() + "."));
        }

        long mod = it.modifiedMs();
        long created = it.createdMs();
        long access = it.trustedAccessMs();
        if (mod > now + FUTURE_TOLERANCE || created > now + FUTURE_TOLERANCE || access > now + FUTURE_TOLERANCE) {
            return new ActivityAssessment(ActivityClass.UNKNOWN, Confidence.NONE, 0, -1,
                    List.of("This file has timestamps in the future (clock or copy issue), so its activity cannot be"
                            + " determined reliably. Automatic optimization is disabled for this item."));
        }

        boolean accessReliable = in.accessTracking() == AccessTracking.ENABLED
                || in.accessTracking() == AccessTracking.COARSE;

        // Collect positive activity evidence: the most recent one determines activity.
        long last = 0;
        String lastSource = null;
        if (mod > last) { last = mod; lastSource = "modified"; }
        if (created > last) { last = created; lastSource = "created or copied here"; }
        if (in.openedViaIdleStowMs() > last) { last = in.openedViaIdleStowMs(); lastSource = "opened through IdleStow"; }
        if (accessReliable && access > last) { last = access; lastSource = "accessed (reliable last-access time)"; }

        if (in.userDeclaredActiveMs() > 0) {
            return new ActivityAssessment(ActivityClass.ACTIVE, Confidence.HIGH, Math.max(last, in.userDeclaredActiveMs()),
                    0, List.of("You marked this item as active."));
        }

        long threshold = in.thresholdDays();
        long idleDays = last > 0 ? Math.max(0, Times.daysBetween(last, now)) : -1;
        if (last > 0 && idleDays < threshold) {
            reasons.add(capitalize(lastSource) + " " + ageText(idleDays) + " — within the " + threshold
                    + "-day inactivity threshold.");
            long activeWindow = Math.min(7, Math.max(1, threshold / 4));
            ActivityClass cls = idleDays <= activeWindow ? ActivityClass.ACTIVE : ActivityClass.PROBABLY_ACTIVE;
            return new ActivityAssessment(cls, Confidence.HIGH, last, idleDays, reasons);
        }

        // No recent activity evidence. Decide how much we can trust that absence.
        long firstIndexed = parseMillis(it.firstIndexedUtc(), now);
        long observedDays = Math.max(0, Times.daysBetween(firstIndexed, now));
        boolean observationDone = in.observationDays() == 0 || observedDays >= in.observationDays();
        String observationNote = observationDone ? ""
                : " Observation period: IdleStow has watched this file for " + observedDays + " of "
                + in.observationDays() + " days; autonomous optimization waits until it ends.";

        if (in.userConfirmedInactiveMs() > 0 && in.userConfirmedInactiveMs() >= last) {
            reasons.add("You confirmed this file is inactive. Last recorded activity: " + lastSource + " "
                    + ageText(idleDays) + ".");
            return new ActivityAssessment(ActivityClass.INACTIVE, Confidence.HIGH, last, idleDays, reasons);
        }

        if (accessReliable && access > 0) {
            reasons.add("Not modified or accessed according to reliable available evidence for " + idleDays
                    + " days (threshold " + threshold + ").");
            if (in.accessTracking() == AccessTracking.COARSE) {
                reasons.add("Last-access times on this volume are updated with day-level granularity.");
            }
            if (observationDone) {
                return new ActivityAssessment(ActivityClass.INACTIVE, Confidence.HIGH, last, idleDays, reasons);
            }
            reasons.add(observationNote.strip());
            return new ActivityAssessment(ActivityClass.POTENTIALLY_INACTIVE, Confidence.MEDIUM, last, idleDays, reasons);
        }

        if (in.archival()) {
            reasons.add("Not modified for " + idleDays + " days, and you designated this folder as archival storage.");
            if (!accessReliable) {
                reasons.add("Last-access tracking is unavailable, so IdleStow relies on your archival designation.");
            }
            if (observationDone) {
                return new ActivityAssessment(ActivityClass.INACTIVE, Confidence.HIGH, last, idleDays, reasons);
            }
            reasons.add(observationNote.strip());
            return new ActivityAssessment(ActivityClass.POTENTIALLY_INACTIVE, Confidence.MEDIUM, last, idleDays, reasons);
        }

        reasons.add((last > 0 ? "Not modified for " + idleDays + " days, but " : "")
                + "last-access tracking is unavailable on this volume, so activity cannot be determined confidently."
                + " Automatic optimization is disabled for this item.");
        return new ActivityAssessment(ActivityClass.UNKNOWN, Confidence.LOW, last, idleDays, reasons);
    }

    /** Files indexed by the same scan share one timestamp string, so the last parse is reused. */
    private record Parsed(String iso, long millis) { }

    private static volatile Parsed lastParsed = new Parsed(null, 0);

    private static long parseMillis(String iso, long fallback) {
        if (iso == null) return fallback;
        Parsed p = lastParsed;
        if (iso.equals(p.iso())) return p.millis();
        try {
            long ms = Instant.parse(iso).toEpochMilli();
            lastParsed = new Parsed(iso, ms);
            return ms;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String ageText(long days) {
        if (days < 0) return "at an unknown time";
        if (days == 0) return "today";
        if (days == 1) return "1 day ago";
        return days + " days ago";
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "Active" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
