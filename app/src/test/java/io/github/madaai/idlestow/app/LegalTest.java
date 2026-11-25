package io.github.madaai.idlestow.app;

import io.github.madaai.idlestow.app.ui.Legal;
import io.github.madaai.idlestow.app.util.UiPrefs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegalTest {

    @Test
    void acceptanceIsRecordedPerVersionAndSurvivesReload(@TempDir Path dir) {
        UiPrefs prefs = UiPrefs.forDataDir(dir);
        assertFalse(Legal.accepted(prefs), "a new data folder has not accepted the terms");
        Legal.recordAcceptance(prefs);
        assertTrue(Legal.accepted(UiPrefs.forDataDir(dir)), "acceptance is persisted");
    }

    @Test
    void olderAcceptedVersionMustBeAcceptedAgain(@TempDir Path dir) {
        UiPrefs prefs = UiPrefs.forDataDir(dir);
        prefs.put("legal.termsAcceptedVersion", "2000-01-01");
        prefs.save();
        assertFalse(Legal.accepted(UiPrefs.forDataDir(dir)));
    }

    @Test
    void bundledDocumentsArePresentAndVersionMatches() {
        String terms = Legal.text("TERMS.md");
        assertTrue(terms.contains("_Version: " + Legal.TERMS_VERSION), "TERMS.md version line must match TERMS_VERSION");
        assertTrue(terms.contains("No warranty"));
        assertTrue(Legal.text("PRIVACY.md").contains("Privacy notice"));
        assertTrue(Legal.text("LICENSE").contains("Apache License"));
        assertTrue(Legal.text("THIRD-PARTY-NOTICES.md").length() > 100);
    }
}
