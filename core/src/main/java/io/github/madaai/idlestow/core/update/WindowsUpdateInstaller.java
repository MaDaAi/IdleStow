package io.github.madaai.idlestow.core.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Applies a staged update to the portable Windows installation. A running program cannot replace its own files,
 * so IdleStow writes a small fixed PowerShell script to its data folder, starts it, and exits. The script waits
 * for IdleStow to close, renames the current folder to {@code <name>.previous}, moves the new version into place
 * and starts it. If any step fails it moves the old version back and starts that instead.
 *
 * <p>All values reach the script as parameters (never by editing the script text), so paths cannot inject
 * commands. Only folders IdleStow can prove it owns are deleted: the staging folder (marker file) and an earlier
 * {@code .previous} copy that contains IdleStow.exe.
 */
public final class WindowsUpdateInstaller {

    /** Where the running portable application lives, or empty when not running from a packaged build. */
    public static Optional<Path> appDir() {
        String p = System.getProperty("jpackage.app-path");
        if (p == null || p.isBlank()) return Optional.empty();
        Path exe = Path.of(p).toAbsolutePath();
        Path dir = exe.getParent();
        if (dir == null || !Files.isRegularFile(dir.resolve("IdleStow.exe")) || !Files.isDirectory(dir.resolve("app"))) {
            return Optional.empty();
        }
        return Optional.of(dir);
    }

    /** True on Windows from a packaged build whose folder IdleStow is allowed to replace. */
    public static Optional<String> unsupportedReason() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return Optional.of("Automatic installation is only available on Windows.");
        }
        Optional<Path> dir = appDir();
        if (dir.isEmpty()) {
            return Optional.of("This copy of IdleStow was not started from the portable package, so it cannot update"
                    + " itself. Download the new version from GitHub instead.");
        }
        Path parent = dir.get().getParent();
        if (parent == null || !Files.isWritable(parent)) {
            return Optional.of("IdleStow cannot write to " + parent + ". Download the new version from GitHub and"
                    + " extract it yourself.");
        }
        return Optional.empty();
    }

    /** The staging folder for a new version: next to the application, so the final move is a rename. */
    public static Path stagingDir(Path appDir) {
        return appDir.resolveSibling(appDir.getFileName() + ".update");
    }

    public static Path backupDir(Path appDir) {
        return appDir.resolveSibling(appDir.getFileName() + ".previous");
    }

    /**
     * Writes the swap script into {@code workDir} and starts it. The caller must exit promptly afterwards.
     *
     * @param dataDir passed back to the new version when IdleStow was started with --data-dir; may be null
     */
    public static void launch(UpdatePackage.Staged staged, Path appDir, Path workDir, Path dataDir)
            throws IOException {
        Path stageRoot = staged.folder().getParent();
        if (!stageRoot.equals(stagingDir(appDir))) throw new IOException("Unexpected staging location");
        Files.createDirectories(workDir);
        Path script = workDir.resolve("apply-update.ps1");
        Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);
        Path log = workDir.resolve("update.log");
        List<String> cmd = new ArrayList<>(List.of(
                System.getenv().getOrDefault("SystemRoot", "C:\\Windows") + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-File", script.toString(),
                "-WaitPid", Long.toString(ProcessHandle.current().pid()),
                "-AppDir", appDir.toString(),
                "-NewDir", staged.folder().toString(),
                "-StageRoot", stageRoot.toString(),
                "-BackupDir", backupDir(appDir).toString(),
                "-Log", log.toString()));
        if (dataDir != null) {
            cmd.add("-DataDir");
            cmd.add(dataDir.toString());
        }
        new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
    }

    static final String SCRIPT = """
            # IdleStow updater: swaps the application folder after IdleStow exits. Written by IdleStow.
            param([int]$WaitPid, [string]$AppDir, [string]$NewDir, [string]$StageRoot, [string]$BackupDir,
                  [string]$Log, [string]$DataDir = '')
            $ErrorActionPreference = 'Stop'
            function Write-UpdateLog([string]$m) { Add-Content -LiteralPath $Log -Value ((Get-Date -Format o) + ' ' + $m) }
            function Start-IdleStow {
                $exe = Join-Path $AppDir 'IdleStow.exe'
                if (-not (Test-Path -LiteralPath $exe)) { return }
                if ($DataDir) { Start-Process -FilePath $exe -ArgumentList @('--data-dir', ('"' + $DataDir + '"')) }
                else { Start-Process -FilePath $exe }
            }
            $moved = $false
            try {
                $p = Get-Process -Id $WaitPid -ErrorAction SilentlyContinue
                if ($p -and -not $p.WaitForExit(180000)) { Write-UpdateLog 'IdleStow did not exit; update not applied'; exit 1 }
                if (Test-Path -LiteralPath $BackupDir) {
                    if (Test-Path -LiteralPath (Join-Path $BackupDir 'IdleStow.exe')) {
                        Remove-Item -LiteralPath $BackupDir -Recurse -Force
                    } else { Write-UpdateLog "$BackupDir exists and is not an IdleStow folder; update not applied"; exit 1 }
                }
                for ($i = 0; $i -lt 40; $i++) {
                    try { Rename-Item -LiteralPath $AppDir -NewName (Split-Path -Leaf $BackupDir); $moved = $true; break }
                    catch { if ($i -eq 39) { throw } ; Start-Sleep -Milliseconds 500 }
                }
                Move-Item -LiteralPath $NewDir -Destination $AppDir
                Remove-Item -LiteralPath $StageRoot -Recurse -Force -ErrorAction SilentlyContinue
                Write-UpdateLog "Update applied; previous version kept in $BackupDir"
                Start-IdleStow
            } catch {
                Write-UpdateLog ('Update failed: ' + $_)
                if ($moved -and -not (Test-Path -LiteralPath $AppDir)) {
                    try { Rename-Item -LiteralPath $BackupDir -NewName (Split-Path -Leaf $AppDir); Write-UpdateLog 'Previous version restored' }
                    catch { Write-UpdateLog ('Could not restore the previous version: ' + $_) }
                }
                Start-IdleStow
                exit 1
            }
            """;
}
