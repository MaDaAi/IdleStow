# Testing

IdleStow changes files on people's disks, so the tests are built around one rule: **they use synthetic data only**, in
temporary folders. Nothing in the suite should ever touch a real user folder.

The latest recorded results, with dates and environment, are in [TEST_RESULTS.md](TEST_RESULTS.md). This guide describes
how the suites are organized and how to run them. It does not repeat counts, which change as tests are added.

## Test layers

| Layer | Where | Framework | What it covers | Special conditions |
|---|---|---|---|---|
| Python unit and IPC | `python/tests/` | pytest | Analysis and sampling, archives (ZIP and tar.zst), fixtures, the worker protocol end to end over stdio, and misc helpers | The tar.zst cases skip when `zstandard` is not installed |
| Python lint | `python/` | Ruff | Style and bug rules for the whole package and tests | Configured in `python/pyproject.toml` |
| Core unit, scenario and crash | `core/src/test/` | JUnit 5 | Activity classification, policy and protections, authorization, settings, database, scanner, and worker robustness. Scenario tests in `EngineScenariosTest` run the engine with `FakePlatform` and the **real Python worker**. `CrashRecoveryTest` injects failures at named points in the job state machine. `SecurityRegressionTest` holds regressions for past security findings. | `FakePlatform` is a mock. It simulates native compression and does not prove NTFS behaviour |
| Platform (native NTFS) | `platform/src/test/` | JUnit 5 | JNA calls, parsers and pure logic. Real NTFS tests are tagged `native` and run only on Windows: `WindowsNativeTest` and `NativeEndToEndTest` | `@Tag("native")` and `@EnabledOnOs(OS.WINDOWS)`. Symlink tests skip without the privilege |
| App | `app/src/test/` | JUnit 5 | Helpers (`Fmt`, `CsvWriter`, `HistoryExport`, `UiPrefs`, `Coalescer`), `LaunchArgs`, and `SmokeTest` | `SmokeTest` is tagged `gui`. It builds every view and needs a desktop session |
| Benchmarks | `platform/src/test/.../IndexingBenchmark.java`, `python/idlestow/benchmarking/` | JUnit 5 and the Python CLI | Throughput of indexing and of worker operations | `IndexingBenchmark` is tagged `benchmark` and excluded by default |

Test support lives in `core/src/test/.../testsupport/`: `FakePlatform`, `TestEnv` (which locates the real worker), `Items`,
`LinkSupport`. The Python harness is `python/tests/helpers.py`.

## Commands

From the repository root, in PowerShell:

```powershell
./scripts/test.ps1          # Ruff, pytest, then ./mvnw verify. GUI smoke tests included; benchmarks excluded.
./scripts/test.ps1 -NoGui   # same, but also excludes the gui-tagged tests
```

The individual suites:

```powershell
cd python
python -m ruff check .
python -m pytest -q
cd ..

./mvnw -q -pl core test
./mvnw -q -pl platform test
./mvnw -q -pl app test
./mvnw -B verify "-Dsurefire.excludedGroups=gui,benchmark"   # what CI runs
```

`-Dsurefire.excludedGroups` is the JUnit tag filter. The default in the root `pom.xml` is `benchmark`, so GUI smoke tests
run unless you exclude `gui`. CI passes `gui,benchmark`, because the GUI smoke test needs an interactive desktop
and the CI workflow says so.

### Worker discovery in tests

The core, platform and app modules set the system property `idlestow.python.dir` to the source `python\` folder for the test
JVM. The worker then starts with `python` on `PATH` (Windows) or `python3` (other systems). Set `IDLESTOW_PYTHON` to the
interpreter that has the dev tools if your system Python does not have `zstandard` and the package installed, for
example `python\.venv\Scripts\python.exe` after `setup-dev.ps1`.

## Benchmarks

Benchmarks are opt-in because they are slow and depend on the machine.

```powershell
# Indexing: writes platform\target\benchmark-indexing.md
./mvnw -pl platform test -Dtest=IndexingBenchmark -Dsurefire.excludedGroups= -Dbench.sizes=1000,10000,100000

# Worker throughput: prints JSON to stdout. Save it yourself if you want to keep it.
cd python
python -m idlestow.benchmarking --size-mib 64 --repeat 3
```

Recorded results live in `benchmarks/results/`. Copy results there only from a real run, and note the machine and date.
Benchmark reports under `target\` are not committed.

## Rules for tests

* **Synthetic data only.** Use JUnit `@TempDir` and pytest `tmp_path`, or `python -m idlestow.fixtures --out <empty folder>`
  to generate files. The fixture generator refuses a non-empty target unless `--force` is given.
* **Temporary files stay under `target\`.** The surefire configuration sets `java.io.tmpdir` to the module's `target`
  folder. The OS temp folder is under `AppData`, which IdleStow protects, so tests would otherwise fail.
* **Do not weaken or skip a test to make a build pass.** If a test is platform-dependent, say why in the test and in
  [KNOWN_ISSUES.md](KNOWN_ISSUES.md).
* **Crash and state-machine changes** update `CrashRecoveryTest` and `docs/architecture/recovery-state-machine.md`.
* **Security fixes** get a regression test in `SecurityRegressionTest` or `PolicyEngineTest`.
* **Tag accordingly.** Use `@Tag("native")` for real-NTFS tests, `@Tag("gui")` for desktop-session tests, and
  `@Tag("benchmark")` for benchmarks. Use pytest markers the same way when a test needs an optional dependency
  (`requires_zstd` in `conftest.py`).
* **Test behaviour, not implementation.** Engine scenarios should assert on what happens to files and on the recorded
  journal, not on internal call order.

## CI

`.github/workflows/ci.yml` runs on pushes to `main`, pull requests and manual dispatch:

* Python: Ruff and pytest on Ubuntu, Windows and macOS.
* Java: `./mvnw -B -ntp verify "-Dsurefire.excludedGroups=gui,benchmark"` on Ubuntu, Windows and macOS. Surefire reports
  are uploaded as artifacts.
* Dependency audit: `pip-audit` on the pinned `zstandard`, and the Maven dependency tree.

`.github/workflows/package-windows.yml` packages the app on Windows and runs the smoke and self-test commands from
[build and packaging](build-and-packaging.md#verifying-the-package).

**The workflows have not run yet.** The repository has not been pushed to GitHub, so CI results are not recorded. The
native tests run only on Windows. On Linux and macOS, the adapters are implemented but not verified (KI-1 in
[KNOWN_ISSUES.md](KNOWN_ISSUES.md)).

## Known gaps

Recorded in [PROJECT_STATE.md](PROJECT_STATE.md) and [TEST_RESULTS.md](TEST_RESULTS.md):

* No automated GUI interaction tests. Only the smoke test loads the views.
* No automated long-running test of background scheduling or the tray menu.
* Engine scenario tests rely on creation times, which Linux does not allow to be set. They need a clock-offset approach
  before they can run on Linux.
* Linux and macOS adapters have not been run on real systems.
