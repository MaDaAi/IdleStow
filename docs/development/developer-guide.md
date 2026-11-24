# Developer guide

This guide is for people changing IdleStow's code. It explains the layout, where the safety-critical code lives, and
how to make the common kinds of change. Read [CONTRIBUTING.md](../../CONTRIBUTING.md)
first, and [build and packaging](build-and-packaging.md) and [testing](testing.md) for the commands.

## Repository layout

```text
core/        Java 21 business logic. No UI, no OS-specific code.
platform/    OS adapters behind core's platform interfaces (Windows/NTFS via JNA; Linux and macOS stubs).
app/         JavaFX desktop UI, tray, launcher, logging configuration, and Java tests for the UI helpers.
python/      Worker package `idlestow` (compression, verification, analysis, fixtures, benchmarks) and pytest tests.
scripts/     Build, test, run and packaging scripts (PowerShell, with POSIX counterparts for build, test and run).
packaging/   Windows icon, pinned checksum file, and the packaging cache and build output (git-ignored).
benchmarks/  Recorded benchmark output.
assets/      Branding sources.
docs/        Architecture, decisions (ADRs), security, user guide and development documents.
.github/     CI workflows, issue and pull request templates, dependabot.
```

## Modules

| Module | Responsibility | Key types |
|---|---|---|
| `core` | All business logic. Testable without a GUI or an OS adapter. | `IdleStowEngine` (façade), `Scanner`, `FileWatcher`, `ActivityClassifier`, `PolicyEngine`, `ProtectedRules`, `AnalysisService`, `JobManager`, `OperationExecutor`, `Authorizer`, `CrashRecovery`, `SafeMove`, `Database`, `WorkerClient`, `PythonWorker`, `SettingsStore`, `Settings` |
| `platform` | OS adapters that implement `core.platform` interfaces. | `Platforms.detect()`, `WindowsPlatform`, `WindowsInspector`, `WindowsCompression`, `LinuxPlatform`, `MacPlatform` |
| `app` | JavaFX UI, tray, single-instance handling, background mode, launcher. | `Launcher`, `IdleStowApp`, `Bootstrap`, `MainWindow`, `ViewId`, `views/*`, `OnboardingWizard`, `PlanPreviewDialog`, `TrayService` |
| `python/idlestow` | Compression analysis, archives, verification, fixtures, benchmarks, and the worker process. | `worker.py`, `ipc/` (protocol, framing, ops), `analysis/`, `compression/`, `verification/`, `fixtures.py`, `benchmarking/` |

Module dependencies run one way: `app` depends on `core` and `platform`, and `platform` depends on `core`. `core`
never imports `platform` or `app`. The engine receives its platform through `EngineConfig`, and `FakePlatform` in the
core tests replaces it.

The engine is a façade. The UI calls `IdleStowEngine` only. It does not reach into repositories, jobs, or the file
system.

## Where safety-critical code lives

Read `docs/architecture/safety-model.md` before you change any of these. The short version is that IdleStow changes
user files, so these rules are the product.

| Area | Files | What must stay true |
|---|---|---|
| The only code that mutates user files | `core/jobs/OperationExecutor.java`, `JobManager.java`, `SafeMove.java` | Every mutation goes through the executor. It revalidates authorization, identity and content immediately before acting. Outputs are created exclusively; nothing is overwritten. |
| Authorization | `core/jobs/Authorizer.java`, `core/engine/IdleStowEngine.java` (`autonomyGate`, `digest`, `confirmAutonomy`) | Revoking a folder takes effect for the next file, even mid-job. Autonomy requires a confirmed policy digest and never uses archive replacement. |
| Mandatory protections | `core/policy/ProtectedRules.java`, `PolicyEngine.java`, `core/platform/ProtectedLocations.java`, `platform/windows/WindowsPlatform.java` (protected directories) | No user policy overrides them. Protection checks run on the canonical path as well as the typed path. |
| Activity classification | `core/activity/ActivityClassifier.java`, `docs/architecture/activity-model.md` | Unknown stays unknown. Old modification time alone is not evidence. IdleStow's own reads are ignored. |
| Crash recovery and the state machine | `core/jobs/CrashRecovery.java`, `core/db/RecoveryRepository.java`, `core/db/JobRepository.java` | Interrupted work is completed, rolled back, or flagged. Nothing ambiguous is deleted. Update `CrashRecoveryTest` and `docs/architecture/recovery-state-machine.md` whenever the state machine changes. |
| Verification | `python/idlestow/verification/`, `core/jobs/OperationExecutor.java` | SHA-256 before and after each operation. Archives are fully decompressed and hashed before the original moves. |
| Worker boundary | `core/ipc/WorkerClient.java`, `WorkerLauncher.java`, `python/idlestow/ipc/` | The worker only reads the paths it is given and creates only the output paths it is given. Messages are validated and capped at 1 MiB. Any change to the protocol updates `docs/architecture/ipc-protocol.md`. |
| Persistence | `core/db/Database.java`, `core/db/Sql.java`, `core/db/*Repository.java`, `core/src/main/resources/db/migration/` | SQL is parameterized. Released migrations are never edited. |

The packaged worker runs with `-s` and `-B`. The development worker runs without `-s` (see KI-8 in
[KNOWN_ISSUES.md](KNOWN_ISSUES.md)).

## Coding rules

These come from CONTRIBUTING.md. They are not style preferences.

* **Never test on real personal files.** Use JUnit `@TempDir`, pytest `tmp_path`, or `python -m idlestow.fixtures` to
  generate synthetic data.
* **Only the job engine mutates user files.** UI code calls `IdleStowEngine`.
* **Keep authorization, identity revalidation, journaling and verification intact.** If you change the state machine,
  update `docs/architecture/recovery-state-machine.md` and `CrashRecoveryTest`.
* **All SQL is parameterized.** Add a new migration instead of editing a released one.
* **IPC changes update `docs/architecture/ipc-protocol.md` and both sides' tests.**
* **Do not add a dependency** without a clear need, a license review, and a pinned version.
* **Do not weaken or skip tests** to make a build pass.
* **Never fabricate results.** Test counts, benchmarks and platform support come from real runs, recorded in
  `docs/development/TEST_RESULTS.md`.
* **Commit and push only when the owner asks.** One logical change per pull request, with tests.
* Java: release 21, `-Xlint:all` (see the root `pom.xml`). Indentation is four spaces. Files end with a newline.
* Python: Python 3.12 or later, line length 110, Ruff rules `E, F, W, I, B, UP, S` (see `python/pyproject.toml`).

## How to add a database migration

1. Pick the next version number. The only migration today is `V1__initial.sql`.
2. Create `core/src/main/resources/db/migration/V2__short_description.sql`. Write plain SQL. Use parameterized statements
   from the code that reads it, never string concatenation.
3. Append an entry to `Database.MIGRATIONS` in `core/src/main/java/io/github/madaai/idlestow/core/db/Database.java`:

   ```java
   new String[]{"2", "short description", "/db/migration/V2__short_description.sql"}
   ```

4. Run `core` tests, especially `DatabaseTest`.
5. Update `docs/architecture/database.md`.

Never edit a migration that has been released. Migrations run in order, each in its own transaction, and each one is
recorded in `schema_migrations`. A database whose schema version is newer than the build is refused, so an older
build never opens a newer database.

## How to add a setting

Settings are a validated immutable record, `core/src/main/java/io/github/madaai/idlestow/core/settings/Settings.java`.

1. Add the component to the `Settings` record, in the right group.
2. In `fromMap`, read the key with a safe default and a range check. Malformed or out-of-range values fall back to
   the default, so a damaged configuration never enables anything.
3. Add the key to `toMap`. `with(key, value)` rejects keys that `toMap` does not contain.
4. If the setting affects autonomous behaviour, include it in the autonomy policy. Any change to the policy then
   invalidates the confirmed digest. Check `IdleStowEngine.currentAutonomyPolicy`.
5. Add a control in `app/.../ui/views/SettingsView.java` that calls `put(key, value)` or one of its helpers.
6. Add tests in `SettingsTest` (parameterized defaults and bounds) and in `SettingsAutonomyEngineTest` when autonomy is
   affected.
7. Document the default in `docs/user-guide/getting-started.md`.

Note: `perf.maxTempBytes` ("Temporary space limit" in the UI) is stored and validated, but no engine code reads it. Treat
it as not enforced until someone wires it up.

## How to add an IPC operation

The protocol is version 1 on both sides (`PROTOCOL` in `WorkerClient.java`, `PROTOCOL_VERSION` in
`python/idlestow/__init__.py`). The worker reads and writes JSON lines on stdio. See
`docs/architecture/ipc-protocol.md`.

1. **Python.** Write the handler in `python/idlestow/ipc/ops.py` with the signature `op_name(params, ctx)`. Validate every
   parameter with the helpers in `ipc/params.py` and return a plain dictionary. Register it in `WORK_OPS`. Put the logic
   itself in the domain package (`analysis/`, `compression/`, `verification/`), not in `ops.py`.
2. **Java.** Add a typed method to `OptimizationWorker`, and implement it in `PythonWorker` by calling
   `client.request(op, params, listener, inactivity)`. Use the long-running variant with cancellation for anything that
   streams data. Map the result into a record.
3. **Tests.** Add a pytest case in `python/tests/test_ipc.py` (protocol and validation). Add the Java side to
   `WorkerClientRobustnessTest` or a `PythonWorker` test. Include an invalid-input case that must be rejected.
4. **Docs.** Update `docs/architecture/ipc-protocol.md` in the same change.

If you change the wire format rather than adding an operation, bump the protocol version on both sides and handle the
mismatch. Existing worker responses, such as `UNSUPPORTED_VERSION`, are the model.

## How to add a platform feature

Platform code sits behind the interfaces in `core/src/main/java/io/github/madaai/idlestow/core/platform/`:
`PlatformServices` and the types it returns (file inspector, native compression, startup registration, power status and
protected locations).

1. Add the method to the interface, with a documented contract. Give it a safe default in `PortablePlatform` if an
   adapter may not support it.
2. Implement it in `platform/windows` (JNA, NTFS), and in `platform/linux` and `platform/macos` if relevant. Linux and
   macOS are implemented but not verified, so say so in their javadoc and in `docs/platform-compatibility.md`.
3. Implement it in `FakePlatform` (core test support). The fake must simulate the behaviour and must say it is a mock.
4. Add native tests in `platform/src/test` with `@Tag("native")` and `@EnabledOnOs(OS.WINDOWS)` for anything that
   needs a real file system. Unit-test parsers and pure logic without a tag.
5. Update the platform matrix in `README.md` and `docs/platform-compatibility.md` only with real evidence.

Changing protection rules is a safety change. Add a regression test in `PolicyEngineTest` or `SecurityRegressionTest`.

## How to add a view

Views are listed in `app/.../ui/ViewId.java`, in sidebar order. Each view extends `BaseView`, declares the engine events
it cares about (`events()`), and is registered in `MainWindow`'s factory map.

* Keyboard shortcuts: `MainWindow.NUMBER_KEYS` has exactly seven entries, one per view (Ctrl+1 to Ctrl+7). Adding an
  eighth view needs a new accelerator. Without it the loop at `installShortcuts` fails.
* UI code must not touch the file system except to open a file or folder with the OS. Mutations go through the engine.
* Run the smoke test (`app` `SmokeTest`, tagged `gui`) to confirm the view builds.

## Where to read next

* [Architecture overview](../architecture/overview.md), [safety model](../architecture/safety-model.md),
  [activity model](../architecture/activity-model.md), [database](../architecture/database.md),
  [IPC protocol](../architecture/ipc-protocol.md), [recovery state machine](../architecture/recovery-state-machine.md).
* [Decision records](../decisions/README.md) explain why the architecture is the way it is.
* [Threat model](../security/threat-model.md).
* [Known issues](KNOWN_ISSUES.md) lists the limitations you should not "fix" by accident.
