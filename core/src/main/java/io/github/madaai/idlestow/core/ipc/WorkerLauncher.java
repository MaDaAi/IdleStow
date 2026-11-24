package io.github.madaai.idlestow.core.ipc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Locates the Python runtime and the {@code idlestow} worker package.
 *
 * <p>Resolution order (deterministic):
 * <ol>
 *   <li>System property {@code idlestow.worker.home}/env {@code IDLESTOW_WORKER_HOME}: a bundled layout
 *       containing {@code python/python.exe} (or {@code python/bin/python3}) and {@code app/idlestow/}</li>
 *   <li>The packaged application image: {@code <app dir>/worker/...} (jpackage sets {@code jpackage.app-path})</li>
 *   <li>Development: {@code idlestow.python.dir}/the {@code python/} folder of the source tree,
 *       run with the interpreter from {@code IDLESTOW_PYTHON} or {@code python}/{@code python3} on PATH</li>
 * </ol>
 */
public record WorkerLauncher(List<String> command, Path workingDir, String pythonPath, boolean bundled) {

    public static Optional<WorkerLauncher> locate() {
        String home = firstNonBlank(System.getProperty("idlestow.worker.home"), System.getenv("IDLESTOW_WORKER_HOME"));
        if (home != null) {
            Optional<WorkerLauncher> b = bundled(Path.of(home));
            if (b.isPresent()) return b;
        }
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) {
            Path exe = Path.of(appPath).toAbsolutePath();
            for (Path base = exe.getParent(); base != null; base = base.getParent()) {
                Optional<WorkerLauncher> b = bundled(base.resolve("worker"));
                if (b.isPresent()) return b;
                Optional<WorkerLauncher> b2 = bundled(base.resolve("app").resolve("worker"));
                if (b2.isPresent()) return b2;
                if (base.getParent() == null || base.equals(exe.getRoot())) break;
            }
        }
        String dev = firstNonBlank(System.getProperty("idlestow.python.dir"), System.getenv("IDLESTOW_PYTHON_DIR"));
        Path devDir = dev != null ? Path.of(dev) : Path.of("python");
        if (Files.isDirectory(devDir.resolve("idlestow"))) {
            String py = firstNonBlank(System.getenv("IDLESTOW_PYTHON"), System.getProperty("idlestow.python.exe"));
            if (py == null) py = isWindows() ? "python" : "python3";
            Path dir = devDir.toAbsolutePath().normalize();
            return Optional.of(new WorkerLauncher(command(py, false), dir, dir.toString(), false));
        }
        return Optional.empty();
    }

    private static Optional<WorkerLauncher> bundled(Path workerHome) {
        Path py = isWindows() ? workerHome.resolve("python").resolve("python.exe")
                : workerHome.resolve("python").resolve("bin").resolve("python3");
        Path app = workerHome.resolve("app");
        if (Files.isRegularFile(py) && Files.isDirectory(app.resolve("idlestow"))) {
            Path dir = app.toAbsolutePath().normalize();
            return Optional.of(new WorkerLauncher(command(py.toAbsolutePath().toString(), true), dir, dir.toString(), true));
        }
        return Optional.empty();
    }

    private static List<String> command(String python, boolean bundled) {
        List<String> c = new ArrayList<>();
        c.add(python);
        c.add("-u");
        c.add("-B");
        if (bundled) c.add("-s"); // bundled runtimes never read the user's site-packages
        c.add("-X");
        c.add("utf8");
        c.add("-m");
        c.add("idlestow.worker");
        return List.copyOf(c);
    }

    private static String firstNonBlank(String... v) {
        for (String s : v) if (s != null && !s.isBlank()) return s;
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }
}
