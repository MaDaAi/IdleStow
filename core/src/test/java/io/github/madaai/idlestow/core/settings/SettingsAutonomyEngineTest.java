package io.github.madaai.idlestow.core.settings;

import io.github.madaai.idlestow.core.db.Sql;
import io.github.madaai.idlestow.core.engine.EngineScenariosTestAccess;
import io.github.madaai.idlestow.core.engine.IdleStowEngine;
import io.github.madaai.idlestow.core.ipc.OptimizationWorker;
import io.github.madaai.idlestow.core.model.Enums.AuthorizationMode;
import io.github.madaai.idlestow.core.model.Enums.Confidence;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.testsupport.FakePlatform;
import io.github.madaai.idlestow.core.testsupport.TestEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "A damaged config can never enable autonomy", verified through the engine. */
class SettingsAutonomyEngineTest {
    static OptimizationWorker worker;

    @TempDir Path tmp;
    IdleStowEngine engine;

    @BeforeAll
    static void startWorker() { worker = TestEnv.worker(); }

    @AfterAll
    static void stopWorker() { worker.close(); }

    @BeforeEach
    void setUp() throws Exception {
        engine = newEngine();
    }

    @AfterEach
    void tearDown() { engine.close(); }

    private IdleStowEngine newEngine() throws Exception {
        return TestEnv.engine(tmp.resolve("data"), new FakePlatform(), EngineScenariosTestAccess.nonClosing(worker), null);
    }

    private void save(Settings s) throws Exception {
        engine.settingsStore().save(s);
    }

    @Test
    void freshEngineIsNotAutonomous() {
        assertEquals(AuthorizationMode.ASK, engine.settings().mode());
        assertFalse(engine.autonomyActive());
        assertNotNull(engine.autonomyGate());
    }

    @Test
    void autonomousModeWithEmptyDigestIsNotActive() throws Exception {
        save(engine.settings().with("onboarding.completed", "true").with("auth.mode", "AUTONOMOUS"));
        assertEquals(AuthorizationMode.AUTONOMOUS, engine.settings().mode());
        assertFalse(engine.autonomyActive());
        assertNotNull(engine.autonomyGate());
    }

    @Test
    void autonomousModeWithGarbageDigestIsNotActive() throws Exception {
        for (String garbage : new String[]{"garbage", "0000", "  ", "x".repeat(10_000), "'; DROP TABLE settings;--"}) {
            save(engine.settings().with("onboarding.completed", "true").with("auth.mode", "AUTONOMOUS")
                    .with("auth.autonomyDigest", garbage));
            assertFalse(engine.autonomyActive(), "digest of length " + garbage.length());
        }
    }

    @Test
    void damagedPersistedSettingsRowsNeverActivateAutonomy() throws Exception {
        // Simulate hand-edited / corrupted rows written straight into the settings table.
        engine.database().tx(c -> {
            for (String[] kv : new String[][]{{"auth.mode", "autonomous"}, {"auth.autonomyDigest", "deadbeef"},
                    {"onboarding.completed", "true"}, {"bg.enabled", "true"}, {"policy.minConfidenceAuto", "NONE"}}) {
                Sql.update(c, "INSERT INTO settings(key,value,updated_utc) VALUES(?,?,'x')"
                        + " ON CONFLICT(key) DO UPDATE SET value=excluded.value", kv[0], kv[1]);
            }
        });
        Settings reloaded = new SettingsStore(engine.database(), Clock.systemUTC()).get();
        assertEquals(AuthorizationMode.AUTONOMOUS, reloaded.mode());
        assertEquals(Confidence.MEDIUM, reloaded.minConfidenceAuto());
        engine.close();
        engine = newEngine();
        assertEquals(AuthorizationMode.AUTONOMOUS, engine.settings().mode());
        assertFalse(engine.autonomyActive(), "a digest nobody confirmed must not activate autonomy");
    }

    @Test
    void confirmedAutonomyIsActiveAndBecomesInactiveWhenPolicyChanges() throws Exception {
        Path folder = Files.createDirectories(tmp.resolve("Documents"));
        MonitoredRoot root = engine.addRoot(folder);
        engine.setRootAutonomy(root.id(), true);
        save(engine.settings().with("onboarding.completed", "true"));
        engine.confirmAutonomy(engine.currentAutonomyPolicy());
        assertTrue(engine.autonomyActive(), "positive control: an honestly confirmed policy is active");

        save(engine.settings().with("policy.inactivityDays", "45"));
        assertFalse(engine.autonomyActive(), "changing the confirmed policy invalidates the confirmation");
        save(engine.settings().with("policy.inactivityDays", "30"));
        assertTrue(engine.autonomyActive(), "restoring the exact policy restores the confirmation");

        save(engine.settings().with("auth.autonomyDigest", "1".repeat(64)));
        assertFalse(engine.autonomyActive(), "a damaged digest is rejected");
    }

    @Test
    void revokingTheRootInvalidatesConfirmation() throws Exception {
        Path folder = Files.createDirectories(tmp.resolve("Documents"));
        MonitoredRoot root = engine.addRoot(folder);
        engine.setRootAutonomy(root.id(), true);
        save(engine.settings().with("onboarding.completed", "true"));
        engine.confirmAutonomy(engine.currentAutonomyPolicy());
        assertTrue(engine.autonomyActive());
        engine.revokeRoot(root.id());
        assertFalse(engine.autonomyActive());
    }

    @Test
    void leavingAutonomousModeClearsTheDigest() throws Exception {
        Path folder = Files.createDirectories(tmp.resolve("Documents"));
        MonitoredRoot root = engine.addRoot(folder);
        engine.setRootAutonomy(root.id(), true);
        save(engine.settings().with("onboarding.completed", "true"));
        engine.confirmAutonomy(engine.currentAutonomyPolicy());
        engine.setMode(AuthorizationMode.ASK);
        assertEquals("", engine.settings().autonomyConfirmedDigest());
        save(engine.settings().with("auth.mode", "AUTONOMOUS"));
        assertFalse(engine.autonomyActive(), "re-selecting AUTONOMOUS without a new confirmation must not activate");
    }
}
