package io.github.madaai.idlestow.core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

class WindowsUpdateInstallerTest {
    @Test
    void stagingAndBackupAreSiblingsOfTheAppFolder() {
        Path app = Path.of("base", "IdleStow").toAbsolutePath();
        assertEquals(app.resolveSibling("IdleStow.update"), WindowsUpdateInstaller.stagingDir(app));
        assertEquals(app.resolveSibling("IdleStow.previous"), WindowsUpdateInstaller.backupDir(app));
        assertEquals(app.getParent(), WindowsUpdateInstaller.stagingDir(app).getParent());
        assertEquals(app.getParent(), WindowsUpdateInstaller.backupDir(app).getParent());
    }

    @Test
    void scriptTakesParametersAndUsesLiteralPaths() {
        String s = WindowsUpdateInstaller.SCRIPT;
        assertTrue(s.contains("param("));
        for (String p : new String[]{"$WaitPid", "$AppDir", "$NewDir", "$StageRoot", "$BackupDir", "$Log", "$DataDir"}) {
            assertTrue(s.contains(p), p);
        }
        assertTrue(s.contains("-LiteralPath"));
        assertFalse(s.toLowerCase().contains("invoke-expression"));
        assertFalse(s.toLowerCase().contains("iex "));
        // every path-taking cmdlet must use -LiteralPath (no wildcard expansion of user paths)
        for (String line : s.split("\\R")) {
            if (line.matches(".*\\b(Remove-Item|Test-Path|Move-Item|Rename-Item|Add-Content)\\b.*")) {
                assertTrue(line.contains("-LiteralPath"), "not literal: " + line);
            }
        }
    }

    @Test
    @ResourceLock("jpackage.app-path")
    void appDirIsEmptyWithoutJpackageProperty() {
        String old = System.getProperty("jpackage.app-path");
        try {
            System.clearProperty("jpackage.app-path");
            assertTrue(WindowsUpdateInstaller.appDir().isEmpty());
            System.setProperty("jpackage.app-path", "  ");
            assertTrue(WindowsUpdateInstaller.appDir().isEmpty());
            System.setProperty("jpackage.app-path", "Z:/definitely/not/here/IdleStow.exe");
            assertTrue(WindowsUpdateInstaller.appDir().isEmpty());
        } finally {
            if (old == null) System.clearProperty("jpackage.app-path");
            else System.setProperty("jpackage.app-path", old);
        }
    }

    @Test
    @ResourceLock("jpackage.app-path")
    void unsupportedWhenNotPackaged() {
        String old = System.getProperty("jpackage.app-path");
        try {
            System.clearProperty("jpackage.app-path");
            assertTrue(WindowsUpdateInstaller.unsupportedReason().isPresent());
        } finally {
            if (old == null) System.clearProperty("jpackage.app-path");
            else System.setProperty("jpackage.app-path", old);
        }
    }
}
