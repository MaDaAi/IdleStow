# Contributing to IdleStow

Thanks for your interest! IdleStow changes files on people's disks, so correctness and safety come first.

## Getting started

Requirements for development: JDK 21+, Python 3.12+, Git. Maven is provided by the wrapper.

```powershell
./scripts/setup-dev.ps1      # creates python/.venv and installs dev tools
./scripts/test.ps1           # Java + Python tests
./scripts/run.ps1            # launches the app with a throw-away data directory
```

See [docs/development/developer-guide.md](docs/development/developer-guide.md) for details.

## Ground rules

* **Never test against real personal files.** Use JUnit `@TempDir`, pytest `tmp_path`, or
  `python -m idlestow.fixtures` to generate synthetic data.
* Only the job engine (`core/jobs`) may mutate user files. UI code must call `IdleStowEngine`.
* Keep authorization, identity revalidation, journaling and verification intact. If you change the
  operation state machine, update `docs/architecture/recovery-state-machine.md` and `CrashRecoveryTest`.
* All SQL must be parameterized. Add a new migration instead of editing a released one.
* Changes to the IPC protocol must update `docs/architecture/ipc-protocol.md` and both sides' tests.
* Do not add dependencies without a clear need, license review and pinned versions.
* Don't weaken or skip tests to make a build pass.

## Pull requests

* One logical change per PR, with tests.
* Run `./mvnw verify` and `cd python && python -m pytest && python -m ruff check .`.
* Describe the safety impact in the PR template.

By contributing you agree that your contributions are licensed under the Apache License 2.0.
