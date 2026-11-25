package io.github.madaai.idlestow.core.engine;

import io.github.madaai.idlestow.core.testsupport.FakePlatform;
import io.github.madaai.idlestow.core.testsupport.TestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pause state shown in the tray menu and the Simple view. */
class PauseStateTest {

    @TempDir Path tmp;

    @Test
    void pausedUntilReflectsTimedIndefiniteAndResumed() throws Exception {
        try (IdleStowEngine e = TestEnv.engine(tmp.resolve("data"), new FakePlatform(), TestEnv.worker(), null)) {
            assertFalse(e.paused());
            assertEquals(0, e.pausedUntilMs());

            long before = System.currentTimeMillis();
            e.pauseAll(Duration.ofHours(1));
            assertTrue(e.paused());
            long until = e.pausedUntilMs();
            assertTrue(until >= before + Duration.ofMinutes(59).toMillis() && until != Long.MAX_VALUE,
                    "a timed pause reports its end time");

            e.pauseAll(null);
            assertEquals(Long.MAX_VALUE, e.pausedUntilMs(), "an indefinite pause replaces the timed one");

            e.resumeAll();
            assertFalse(e.paused());
            assertEquals(0, e.pausedUntilMs());
        }
    }
}
