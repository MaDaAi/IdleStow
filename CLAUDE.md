# CLAUDE.md — working on IdleStow

IdleStow ("Idle files. Reclaimed space.") is a Java 21 + Python desktop app that indexes user-authorized
folders, classifies file activity conservatively, and performs **verified, reversible** compression of
inactive files (NTFS transparent compression, or ZIP/tar.zst archives through a Python worker).

Start here: `docs/development/PROJECT_STATE.md` (current status), `docs/development/TASK_BOARD.md`,
`docs/architecture/overview.md`, `docs/architecture/safety-model.md`.

## Layout

- `core/` — business logic, no UI: `engine/IdleStowEngine` (façade), `index/`, `activity/`, `policy/`,
  `recommend/`, `jobs/` (the ONLY code that mutates user files), `db/`, `ipc/`, `platform/` (interfaces).
- `platform/` — Windows (JNA/NTFS), Linux, macOS adapters + native/integration tests and benchmarks.
- `app/` — JavaFX UI, tray, launcher (`--background`, `--read-only`, `--data-dir`, `--smoke-test`, `--self-test`).
- `python/idlestow/` — worker (`python -m idlestow.worker`), analysis, compression, verification,
  benchmarking, synthetic fixtures. Protocol: `docs/architecture/ipc-protocol.md`.
- `scripts/` — build/test/run/package scripts. `packaging/windows/` — icon, pinned checksums, caches.

## Commands (bash; PowerShell equivalents in scripts/*.ps1)

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
./mvnw -q -pl core test                      # core tests (uses the real Python worker)
./mvnw -q -pl platform test                  # incl. native NTFS tests on Windows
./mvnw -B verify -Dsurefire.excludedGroups=gui,benchmark
(cd python && python -m pytest -q && python -m ruff check .)
pwsh scripts/package-windows.ps1             # portable app image in dist/windows/IdleStow
```

Test temp dirs are redirected to `target/` (the OS temp dir is under AppData, which IdleStow protects).

## Rules

- Data integrity first. Never test on real user files — synthetic temp data only.
- Keep authorization, identity revalidation, journaling and verification intact; update
  `CrashRecoveryTest` and `recovery-state-machine.md` when the state machine changes.
- Parameterized SQL only; new migrations instead of editing released ones.
- Never fabricate test results, benchmarks or platform support. Record real results in
  `docs/development/TEST_RESULTS.md`.
- Commits: use the configured git identity; no AI co-author trailers (project owner's instruction).
  Never push without explicit permission.
