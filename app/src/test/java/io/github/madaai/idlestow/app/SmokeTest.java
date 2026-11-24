package io.github.madaai.idlestow.app;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts the real application in a separate JVM in --smoke-test mode (needs a display). */
@Tag("gui")
class SmokeTest {
    @Test
    void launcherSmokeTestBuildsAllViews() throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        String py = System.getProperty("idlestow.python.dir");
        if (py != null) cmd.add("-Didlestow.python.dir=" + py);
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("io.github.madaai.idlestow.app.Launcher");
        cmd.add("--smoke-test");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean done = p.waitFor(240, TimeUnit.SECONDS);
        if (!done) p.destroyForcibly();
        assertTrue(done, "smoke test timed out:\n" + out);
        assertEquals(0, p.exitValue(), out);
        assertTrue(out.contains("SMOKE OK"), out);
    }
}
