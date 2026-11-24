package io.github.madaai.idlestow.core.engine;

import io.github.madaai.idlestow.core.ipc.UnavailableWorker;
import io.github.madaai.idlestow.core.testsupport.FakePlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REV-1 #1: the self-test must never delete pre-existing data. */
class SelfTestSafetyTest {

    @TempDir Path tmp;

    @Test
    void nonEmptyDirectoryIsRefusedAndLeftIntact() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("user-folder"));
        Path canary = Files.writeString(dir.resolve("canary.txt"), "precious");
        Files.createDirectories(dir.resolve("sub"));
        SelfTest.Result r = SelfTest.run(dir, new FakePlatform(), new UnavailableWorker("test"));
        assertFalse(r.passed());
        assertEquals("precious", Files.readString(canary));
        assertTrue(Files.isDirectory(dir.resolve("sub")));
    }

    @Test
    void rootsAndHomeAreRefused() {
        assertNotNull(SelfTest.refusal(Path.of(System.getProperty("user.home"))));
        assertNotNull(SelfTest.refusal(tmp.getRoot()));
    }

    @Test
    void newDirectoryIsRemovedAfterwards() {
        Path dir = tmp.resolve("fresh");
        SelfTest.run(dir, new FakePlatform(), new UnavailableWorker("test"));
        assertFalse(Files.exists(dir));
    }
}
