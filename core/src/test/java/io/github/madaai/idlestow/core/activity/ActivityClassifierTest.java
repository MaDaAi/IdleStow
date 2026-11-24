package io.github.madaai.idlestow.core.activity;

import io.github.madaai.idlestow.core.activity.ActivityClassifier.Input;
import io.github.madaai.idlestow.core.model.ActivityAssessment;
import io.github.madaai.idlestow.core.model.Enums.ActivityClass;
import io.github.madaai.idlestow.core.model.Enums.Confidence;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.platform.VolumeInfo.AccessTracking;
import io.github.madaai.idlestow.core.policy.PolicyEngine.Protection;
import io.github.madaai.idlestow.core.policy.PolicyEngine.Restriction;
import io.github.madaai.idlestow.core.testsupport.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;

import static io.github.madaai.idlestow.core.testsupport.Items.DAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityClassifierTest {
    static final long NOW = Instant.parse("2026-06-01T12:00:00Z").toEpochMilli();
    static final String LONG_AGO = Instant.ofEpochMilli(NOW - 400 * DAY).toString();
    static final Path P = Path.of("C:/data/Docs/file.txt");

    final ActivityClassifier classifier = new ActivityClassifier();

    static long ago(long days) { return NOW - days * DAY; }

    static IndexedItem item(long created, long modified, long access, String firstIndexed) {
        return Items.file(P, created, modified, access, firstIndexed);
    }

    static Input in(IndexedItem it, AccessTracking tracking, int threshold) {
        return new Input(it, Protection.NONE, tracking, threshold, false, 0, 0, 0, 0, NOW);
    }

    /** An item untouched for {@code days}, indexed long ago. */
    static IndexedItem old(long days) {
        return item(ago(days), ago(days), ago(days), LONG_AGO);
    }

    @Test
    void recentModificationIsActive() {
        ActivityAssessment a = classifier.classify(in(item(ago(300), ago(1), ago(300), LONG_AGO), AccessTracking.DISABLED, 30));
        assertEquals(ActivityClass.ACTIVE, a.classification());
        assertEquals(Confidence.HIGH, a.confidence());
        assertEquals(1, a.idleDays());
        assertEquals(ago(1), a.lastActivityMs());
        assertTrue(a.explanation().toLowerCase().contains("modified"), a.explanation());
    }

    @Test
    void modifiedTwentyDaysAgoWithThirtyDayThresholdIsProbablyActive() {
        ActivityAssessment a = classifier.classify(in(item(ago(300), ago(20), ago(300), LONG_AGO), AccessTracking.ENABLED, 30));
        assertEquals(ActivityClass.PROBABLY_ACTIVE, a.classification());
        assertEquals(Confidence.HIGH, a.confidence());
        assertEquals(20, a.idleDays());
    }

    @Test
    void oldEvidenceWithEnabledTrackingAndFinishedObservationIsInactiveHigh() {
        ActivityAssessment a = classifier.classify(in(old(120), AccessTracking.ENABLED, 30));
        assertEquals(ActivityClass.INACTIVE, a.classification());
        assertEquals(Confidence.HIGH, a.confidence());
        assertEquals(120, a.idleDays());
    }

    @Test
    void oldEvidenceButObservationNotDoneIsPotentiallyInactiveMedium() {
        String yesterday = Instant.ofEpochMilli(ago(1)).toString();
        IndexedItem it = item(ago(120), ago(120), ago(120), yesterday);
        Input input = new Input(it, Protection.NONE, AccessTracking.ENABLED, 30, false, 14, 0, 0, 0, NOW);
        ActivityAssessment a = classifier.classify(input);
        assertEquals(ActivityClass.POTENTIALLY_INACTIVE, a.classification());
        assertEquals(Confidence.MEDIUM, a.confidence());
        assertTrue(a.explanation().contains("Observation period"), a.explanation());
    }

    @Test
    void observationFinishedAfterEnoughDays() {
        String twentyDaysAgo = Instant.ofEpochMilli(ago(20)).toString();
        IndexedItem it = item(ago(120), ago(120), ago(120), twentyDaysAgo);
        ActivityAssessment a = classifier.classify(new Input(it, Protection.NONE, AccessTracking.ENABLED, 30, false, 14, 0, 0, 0, NOW));
        assertEquals(ActivityClass.INACTIVE, a.classification());
    }

    @Test
    void missingOrGarbageFirstIndexedTimestampCountsAsNotObserved() {
        for (String bad : new String[]{null, "", "not-a-date"}) {
            IndexedItem it = item(ago(120), ago(120), ago(120), bad);
            ActivityAssessment a = classifier.classify(new Input(it, Protection.NONE, AccessTracking.ENABLED, 30, false, 14, 0, 0, 0, NOW));
            assertEquals(ActivityClass.POTENTIALLY_INACTIVE, a.classification(), String.valueOf(bad));
            assertNotEquals(Confidence.HIGH, a.confidence());
        }
    }

    @Test
    void recentTrustedAccessMakesFileActiveEvenWithOldModification() {
        ActivityAssessment a = classifier.classify(in(item(ago(300), ago(300), ago(2), LONG_AGO), AccessTracking.ENABLED, 30));
        assertEquals(ActivityClass.ACTIVE, a.classification());
        assertEquals(ago(2), a.lastActivityMs());
        assertTrue(a.explanation().toLowerCase().contains("accessed"), a.explanation());
    }

    @ParameterizedTest
    @EnumSource(value = AccessTracking.class, names = {"DISABLED", "UNKNOWN"})
    void accessTimeIsIgnoredWhenTrackingIsNotReliable(AccessTracking tracking) {
        ActivityAssessment a = classifier.classify(in(item(ago(300), ago(300), ago(2), LONG_AGO), tracking, 30));
        assertNotEquals(ActivityClass.ACTIVE, a.classification());
        assertNotEquals(ActivityClass.PROBABLY_ACTIVE, a.classification());
    }

    @ParameterizedTest
    @EnumSource(value = AccessTracking.class, names = {"DISABLED", "UNKNOWN"})
    void withoutAccessTrackingOldFilesAreUnknownNeverInactive(AccessTracking tracking) {
        ActivityAssessment a = classifier.classify(in(old(500), tracking, 30));
        assertEquals(ActivityClass.UNKNOWN, a.classification());
        assertNotEquals(Confidence.HIGH, a.confidence());
        assertTrue(a.explanation().toLowerCase().contains("last-access tracking"), a.explanation());
    }

    @Test
    void enabledTrackingWithoutAnyRecordedAccessIsUnknown() {
        ActivityAssessment a = classifier.classify(in(item(ago(500), ago(500), 0, LONG_AGO), AccessTracking.ENABLED, 30));
        assertEquals(ActivityClass.UNKNOWN, a.classification());
    }

    @ParameterizedTest
    @EnumSource(value = AccessTracking.class, names = {"DISABLED", "UNKNOWN"})
    void archivalDesignationMakesOldFilesInactiveWhenTrackingUnavailable(AccessTracking tracking) {
        Input input = new Input(old(500), Protection.NONE, tracking, 30, true, 0, 0, 0, 0, NOW);
        ActivityAssessment a = classifier.classify(input);
        assertEquals(ActivityClass.INACTIVE, a.classification());
        assertTrue(a.explanation().toLowerCase().contains("archival"), a.explanation());
        assertTrue(a.explanation().toLowerCase().contains("last-access tracking"), a.explanation());
    }

    @Test
    void archivalDesignationDoesNotOverrideRecentModification() {
        Input input = new Input(item(ago(500), ago(3), 0, LONG_AGO), Protection.NONE, AccessTracking.DISABLED, 30, true,
                0, 0, 0, 0, NOW);
        assertEquals(ActivityClass.ACTIVE, classifier.classify(input).classification());
    }

    @Test
    void archivalWithUnfinishedObservationIsOnlyPotentiallyInactive() {
        String yesterday = Instant.ofEpochMilli(ago(1)).toString();
        Input input = new Input(item(ago(500), ago(500), 0, yesterday), Protection.NONE, AccessTracking.DISABLED, 30, true,
                14, 0, 0, 0, NOW);
        ActivityAssessment a = classifier.classify(input);
        assertEquals(ActivityClass.POTENTIALLY_INACTIVE, a.classification());
        assertEquals(Confidence.MEDIUM, a.confidence());
    }

    @Test
    void timestampsFarInTheFutureAreUnknownWithNoConfidence() {
        for (IndexedItem it : new IndexedItem[]{
                item(ago(10), NOW + 2 * DAY, ago(10), LONG_AGO),
                item(NOW + 2 * DAY, ago(10), ago(10), LONG_AGO),
                item(ago(10), ago(10), NOW + 2 * DAY, LONG_AGO)}) {
            ActivityAssessment a = classifier.classify(in(it, AccessTracking.ENABLED, 30));
            assertEquals(ActivityClass.UNKNOWN, a.classification());
            assertEquals(Confidence.NONE, a.confidence());
            assertEquals(-1, a.idleDays());
            assertTrue(a.explanation().contains("future"), a.explanation());
        }
    }

    @Test
    void smallClockSkewIntoTheFutureIsTolerated() {
        ActivityAssessment a = classifier.classify(in(item(ago(10), NOW + DAY / 2, ago(10), LONG_AGO), AccessTracking.ENABLED, 30));
        assertEquals(ActivityClass.ACTIVE, a.classification());
        assertEquals(0, a.idleDays());
    }

    @Test
    void recentCreationTimeOfACopiedFileMakesItActive() {
        // copied file: old modification time preserved, fresh creation time
        ActivityAssessment a = classifier.classify(in(item(ago(1), ago(400), ago(400), LONG_AGO), AccessTracking.DISABLED, 30));
        assertEquals(ActivityClass.ACTIVE, a.classification());
        assertTrue(a.explanation().toLowerCase().contains("created or copied"), a.explanation());
    }

    @Test
    void openedThroughIdleStowRecentlyIsActive() {
        Input input = new Input(old(400), Protection.NONE, AccessTracking.DISABLED, 30, false, 0, ago(2), 0, 0, NOW);
        ActivityAssessment a = classifier.classify(input);
        assertEquals(ActivityClass.ACTIVE, a.classification());
        assertTrue(a.explanation().contains("opened through IdleStow") || a.explanation().contains("Opened through IdleStow"));
    }

    @Test
    void userDeclaredActiveWinsOverEverythingElseExceptProtection() {
        Input input = new Input(old(900), Protection.NONE, AccessTracking.ENABLED, 30, true, 0, 0, ago(1), ago(0), NOW);
        ActivityAssessment a = classifier.classify(input);
        assertEquals(ActivityClass.ACTIVE, a.classification());
        assertEquals(Confidence.HIGH, a.confidence());
    }

    @Test
    void userConfirmedInactiveIsInactiveHighEvenWithoutAccessTracking() {
        Input input = new Input(old(200), Protection.NONE, AccessTracking.DISABLED, 30, false, 14, 0, 0, ago(1), NOW);
        ActivityAssessment a = classifier.classify(input);
        assertEquals(ActivityClass.INACTIVE, a.classification());
        assertEquals(Confidence.HIGH, a.confidence());
        assertTrue(a.explanation().contains("You confirmed"), a.explanation());
    }

    @Test
    void userConfirmationIsVoidedByLaterActivity() {
        // file modified 5 days ago, but the user "confirmed inactive" 100 days ago: stale confirmation
        IndexedItem it = item(ago(300), ago(5), ago(300), LONG_AGO);
        Input input = new Input(it, Protection.NONE, AccessTracking.DISABLED, 30, false, 0, 0, 0, ago(100), NOW);
        assertEquals(ActivityClass.ACTIVE, classifier.classify(input).classification());
        // modified 60 days ago (past threshold) but confirmation older than the modification
        IndexedItem it2 = item(ago(300), ago(60), ago(300), LONG_AGO);
        Input input2 = new Input(it2, Protection.NONE, AccessTracking.DISABLED, 30, false, 0, 0, 0, ago(100), NOW);
        assertEquals(ActivityClass.UNKNOWN, classifier.classify(input2).classification());
    }

    @Test
    void pinnedAndExcludedProtectionsShortCircuit() {
        Input pinned = new Input(item(ago(1), ago(1), ago(1), LONG_AGO), new Protection(Restriction.PINNED, "Pinned by you"),
                AccessTracking.ENABLED, 30, false, 0, 0, 0, 0, NOW);
        ActivityAssessment p = classifier.classify(pinned);
        assertEquals(ActivityClass.PINNED, p.classification());
        assertTrue(p.explanation().contains("never be optimized automatically"));

        for (Restriction r : new Restriction[]{Restriction.PROTECTED, Restriction.EXCLUDED, Restriction.OUTSIDE_ROOT, Restriction.REVOKED}) {
            Input in = new Input(old(500), new Protection(r, "reason " + r), AccessTracking.ENABLED, 30, true, 0, 0, 0, ago(1), NOW);
            ActivityAssessment a = classifier.classify(in);
            assertEquals(ActivityClass.EXCLUDED, a.classification(), r.name());
            assertEquals(-1, a.idleDays());
        }
    }

    @Test
    void coarseTrackingAddsGranularityNote() {
        ActivityAssessment a = classifier.classify(in(old(200), AccessTracking.COARSE, 30));
        assertEquals(ActivityClass.INACTIVE, a.classification());
        assertTrue(a.explanation().contains("day-level granularity"), a.explanation());

        ActivityAssessment enabled = classifier.classify(in(old(200), AccessTracking.ENABLED, 30));
        assertTrue(!enabled.explanation().contains("granularity"));
    }

    @Test
    void coarseTrackingAccessCountsAsReliableEvidence() {
        ActivityAssessment a = classifier.classify(in(item(ago(300), ago(300), ago(3), LONG_AGO), AccessTracking.COARSE, 30));
        assertEquals(ActivityClass.ACTIVE, a.classification());
    }

    @ParameterizedTest(name = "threshold {0} days")
    @ValueSource(ints = {7, 14, 30, 60, 90, 180, 365})
    void thresholdBoundaryIsExclusiveForActivity(int threshold) {
        // idle exactly == threshold -> NOT active
        ActivityAssessment at = classifier.classify(in(old(threshold), AccessTracking.ENABLED, threshold));
        assertEquals(ActivityClass.INACTIVE, at.classification(), "idle == threshold must not be active");
        assertEquals(threshold, at.idleDays());

        // one millisecond short of the threshold -> still (probably) active
        long almost = NOW - threshold * DAY + 1;
        IndexedItem justUnder = item(almost, almost, almost, LONG_AGO);
        ActivityAssessment under = classifier.classify(in(justUnder, AccessTracking.ENABLED, threshold));
        assertTrue(under.classification() == ActivityClass.ACTIVE || under.classification() == ActivityClass.PROBABLY_ACTIVE,
                "idle < threshold must be active but was " + under.classification());
        assertEquals(threshold - 1, under.idleDays());

        // one day past -> inactive
        assertEquals(ActivityClass.INACTIVE,
                classifier.classify(in(old(threshold + 1L), AccessTracking.ENABLED, threshold)).classification());
    }

    @ParameterizedTest(name = "threshold {0} days")
    @ValueSource(ints = {7, 14, 30, 60, 90, 180, 365})
    void withoutTrackingTheThresholdBoundaryNeverProducesInactive(int threshold) {
        ActivityAssessment a = classifier.classify(in(old(threshold), AccessTracking.DISABLED, threshold));
        assertEquals(ActivityClass.UNKNOWN, a.classification());
        assertEquals(ActivityClass.UNKNOWN, classifier.classify(in(old(threshold * 3L), AccessTracking.UNKNOWN, threshold)).classification());
    }

    @ParameterizedTest(name = "threshold {0} days")
    @ValueSource(ints = {7, 14, 30, 60, 90, 180, 365})
    void activeVersusProbablyActiveSplit(int threshold) {
        long window = Math.min(7, Math.max(1, threshold / 4));
        assertEquals(ActivityClass.ACTIVE, classifier.classify(in(old(window), AccessTracking.ENABLED, threshold)).classification());
        assertEquals(ActivityClass.PROBABLY_ACTIVE,
                classifier.classify(in(old(window + 1), AccessTracking.ENABLED, threshold)).classification());
    }

    @Test
    void brandNewFileWithNoTimestampsAtAllIsUnknownWithoutTracking() {
        ActivityAssessment a = classifier.classify(in(item(0, 0, 0, LONG_AGO), AccessTracking.DISABLED, 30));
        assertEquals(ActivityClass.UNKNOWN, a.classification());
        assertEquals(-1, a.idleDays());
        assertEquals(0, a.lastActivityMs());
    }
}
