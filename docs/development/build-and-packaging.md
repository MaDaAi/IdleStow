# Build and packaging

This guide covers building IdleStow from source, the development scripts, and producing the Windows portable package.
For tests see [testing](testing.md). For the code layout see the [developer guide](developer-guide.md).

## Prerequisites

| Tool | Needed for | Notes |
|---|---|---|
| JDK 21 | Building and running the app | The scripts look for `JAVA_HOME`, then `C:\Program Files\Java\jdk-21` and `C:\Program Files\Eclipse Adoptium\jdk-21`. The Maven wrapper (`mvnw.cmd`, `mvnw`) is included, so you do not need Maven. |
| Python 3.12 or later on `PATH` | Development worker, fixtures, Python tests | `setup-dev.ps1` creates `python\.venv` with the dev tools. Nothing is installed system-wide. |
| Git | Cloning | |
| PowerShell | The `.ps1` scripts | The `.sh` scripts (`build.sh`, `test.sh`, `run.sh`, `generate-fixtures.sh`) are the POSIX counterparts. |
| Internet access | `package-windows.ps1` only | The script downloads a pinned JDK and CPython the first time. Downloads are cached in `packaging\windows\cache`. |
| WiX Toolset (optional) | An MSI installer | Not installed on the development machine. The MSI is built only when `wix.exe` or `candle.exe` is on `PATH`. See [decision 0007](../decisions/0007-no-installer-without-wix.md). |

## Scripts

Run these from the repository root in PowerShell.

| Script | What it does | Parameters |
|---|---|---|
| `scripts\setup-dev.ps1` | Creates `python\.venv` and installs the Python package in editable mode with the `dev` and `zstd` extras. | none |
| `scripts\build.ps1` | Runs `mvnw.cmd -B package -DskipTests` for all modules. | none |
| `scripts\run.ps1` | Starts the desktop app from `app\target\idlestow.jar`. Builds first if the jar is missing. Sets `-Didlestow.python.dir` to the source `python\` folder. | `-DataDir <dir>` (default `target\dev-data`, a throw-away folder), `-ReadOnly` |
| `scripts\test.ps1` | Runs Ruff, pytest, and `mvnw verify`. | `-NoGui` (skips GUI smoke tests too) |
| `scripts\generate-fixtures.ps1` | Generates synthetic files with `python -m idlestow.fixtures`. | `-Out <empty folder>` (required), `-Preset mixed` or `benchmark`, `-Files <n>`, `-Seed <n>` |
| `scripts\package-windows.ps1` | Builds the portable application image and zip. | `-SkipBuild` (reuse the Maven output) |

The `.sh` scripts take the same roles. `run.sh` accepts a data folder as its first argument.

`generate-fixtures` writes synthetic data only. Point it at an empty folder you created for the purpose, never at a real
folder.

## Packaging the Windows portable app

Run:

```powershell
./scripts/package-windows.ps1
```

The script does four things.

1. **Maven build.** Runs `mvnw -q -B package -DskipTests` unless `-SkipBuild` is given. Copies `app\target\idlestow.jar`
   and `app\target\lib\*.jar` into `packaging\windows\build\input`. It refuses to continue if two versions of the same
   library are present. It then removes the native libraries of other platforms from `sqlite-jdbc` (Linux, Mac, FreeBSD,
   Android, musl) and `jna` (everything except `win32-*`). Only zip entries are dropped; the Windows natives are untouched.
2. **Python worker.**
   * Downloads the official embeddable CPython 3.12.10 zip, if it is not cached, and checks its SHA-256 against
     `packaging\windows\python-embed.sha256`. A mismatch deletes the download and stops the build.
   * Extracts it to `packaging\windows\build\worker\python`. Writes a `._pth` file that lists the standard library zip,
     `.`, `..\app` and `Lib\site-packages`.
   * Installs `zstandard==0.25.0` as a Windows wheel (`win_amd64`, CPython 3.12) into `Lib\site-packages`.
   * Copies the `python\idlestow` package to `worker\app\idlestow`, byte-compiles it with hash-based `.pyc` files (they stay
     valid after unzipping, unlike timestamp-based ones), and probes the bundled worker with `python.exe -s` before
     continuing.
3. **jpackage.** Downloads the pinned Eclipse Temurin 21.0.13.1+2 JDK zip, checks its SHA-256 (hard-coded in the
   script), and extracts it to `packaging\windows\cache\temurin-21`. It then runs `jpackage --type app-image` with:
   * the main jar `idlestow.jar` and main class `io.github.madaai.idlestow.app.Launcher`,
   * a jlinked runtime limited to `java.base`, `java.desktop`, `java.logging`, `java.naming`, `java.net.http`, `java.sql`,
     `java.xml`, `jdk.crypto.ec`, `jdk.jfr`, `jdk.localedata`, `jdk.charsets`, `jdk.management` and `jdk.unsupported`
     (plus their transitive `requires`: `java.datatransfer`, `java.prefs`, `java.management`, `java.security.sasl`,
     `java.transaction.xa`). Before jpackage runs, `jdeps` is run over all jars and the build fails if it reports a module
     that is not in the list. `jdk.zipfs`, `jdk.crypto.cryptoki` and `jdk.accessibility` are deliberately left out. The
     default `cacerts` truststore stays in the runtime,
   * jlink options `--strip-debug --no-man-pages --no-header-files --generate-cds-archive`. The CDS archive of the JDK
     classes (`runtime\bin\server\classes.jsa`) is kept; the second archive for heaps above 32 GB (`classes_nocoops.jsa`) is
     deleted. `--compress=zip-6` is not used: it shrinks the folder but makes the zip larger, because the outer zip
     compresses the uncompressed runtime image better than jlink does,
   * JVM options `-Dfile.encoding=UTF-8`, `--enable-native-access=ALL-UNNAMED`, `-XX:+UseSerialGC` (faster start and a
     smaller footprint than G1 in the measurements below) and the class-data-sharing options described next,
   * a second launcher `IdleStow-cli` built as a console application.

   `IDLESTOW_PACKAGING_JDK` can point at another OpenJDK build instead of the pinned Temurin.
   The script then copies `worker\` into the image, and copies `LICENSE`, `NOTICE`, `THIRD-PARTY-NOTICES.md` and `README.md`.
   Finally it runs the packaged `IdleStow-cli.exe --smoke-test` against a throw-away data folder under
   `packaging\windows\build\smoke` and fails the build unless it prints `SMOKE OK`.
4. **Archives.** Zips the image to `dist\IdleStow-0.1.0-windows-x64-portable.zip`. If WiX is found, it also builds an MSI
   with `jpackage --type msi`. Otherwise it prints a warning that the MSI was not built.

### Startup: class data sharing

The jlinked runtime contains a CDS archive of the JDK classes. In addition the launcher passes
`-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=$APPDIR\IdleStow.jsa`, so the JVM writes an archive of the application
classes when the app first exits and maps it on later starts. The first run after unzipping is slower once.

* The archive is not shipped. The JVM validates it against the absolute jar paths of the machine that built it, so an archive
  made on the build machine is useless after unzipping elsewhere. The packaging run creates one during its smoke test, to
  prove the options work, and then deletes it.
* The JVM also checks jar sizes and timestamps, so a stale archive is never used for changed jars. It creates the file
  read-only and does not replace it, so whoever replaces the jars in `app\` (for example an updater) must delete
  `app\IdleStow.jsa`. Otherwise the app stays correct but starts without the application archive.
* If the folder is not writable, the JVM starts without the archive and prints nothing (`-Xlog:cds*=off`).

### Output

```text
dist\windows\IdleStow\
    IdleStow.exe            GUI launcher
    IdleStow-cli.exe        console launcher (used for --smoke-test and --self-test)
    app\                    IdleStow jars and the dependency jars
    runtime\                bundled Java runtime
    worker\python\          embeddable CPython with zstandard
    worker\app\idlestow\    the Python worker package
    LICENSE, NOTICE, THIRD-PARTY-NOTICES.md, README.md

dist\IdleStow-0.1.0-windows-x64-portable.zip
```

The `IdleStow` folder is the application. The zip contains that folder.

Measured on the development machine with the same JDK and dependencies: the image went from 172,566,213 to 170,460,921
bytes and the zip from 79,389,491 to 70,675,143 bytes. The median `--smoke-test` wall time of the unzipped package went from
3.35 s to 2.22 s once the application CDS archive exists. Your numbers may differ.

The `dist\` folder, `target\` folders and `packaging\windows\cache\` are generated. Git ignores them.

## Verifying the package

From the repository root:

```powershell
# Builds every view against a temporary data folder, prints SMOKE OK and exits.
dist\windows\IdleStow\IdleStow-cli.exe --smoke-test --data-dir C:\IdleStowVerify\data

# End-to-end check on synthetic files, in an EMPTY folder that does not exist yet or is empty.
dist\windows\IdleStow\IdleStow-cli.exe --self-test C:\IdleStowVerify\selftest
```

Notes:

* **Do not use a folder under `AppData`** (including `%TEMP%`, which is under `AppData\Local`). IdleStow protects those
  folders, so the self-test would refuse its own files.
* The self-test requires an empty folder and deletes that folder afterwards. Do not point it at a folder you want to keep.
* Both commands exit 0 on success.

The `package-windows.yml` workflow runs both commands on a Windows runner, on manual dispatch and on `v*` tags, and uploads
the zip. The workflow has not run yet, because the repository is not pushed (see
[PROJECT_STATE.md](PROJECT_STATE.md)).

## Uninstalling

The portable package has no installer and no uninstall step.

1. If you enabled **Start IdleStow when I sign in**, turn it off in **Settings > Automation** first. That removes the
   per-user `HKCU\...\Run` entry. Otherwise the entry points at a folder that no longer exists.
2. Delete the `IdleStow` folder.
3. Optionally delete `%LOCALAPPDATA%\IdleStow`. This removes the index, history, settings, logs and recovery manifests.

Uninstalling never removes your files, archives or retained originals. Restore archived files from **Recovery** before
you delete the data folder, since the database is what lists them.

## Developer run

```powershell
./scripts/setup-dev.ps1        # once: python\.venv
./scripts/build.ps1            # build all modules
./scripts/run.ps1              # run with target\dev-data
./scripts/run.ps1 -DataDir C:\IdleStowDev\data -ReadOnly
```

In development the worker runs from the source `python\` folder. It starts `python` (on Windows) or `python3`, or the
executable named by `IDLESTOW_PYTHON`. Set `IDLESTOW_PYTHON` to the venv interpreter
(`python\.venv\Scripts\python.exe`) if the system Python is not the right one.
