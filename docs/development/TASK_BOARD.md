# Task board

Statuses: Pending · Ready · In progress · Review · Testing · Complete · Blocked

| ID | Description | Agent | Depends on | Status | Acceptance criteria |
|---|---|---|---|---|---|
| ARCH-1 | Contracts: platform interfaces, IPC protocol v1, DB schema V1, safety model | Lead | — | Complete | Docs in `docs/architecture/`; core compiles |
| PY-1 | Python worker (IPC, analysis, zip/tar.zst, verification, fixtures, benchmarks) | python-optimization-engineer | ARCH-1 | Complete | 171 pytest pass, ruff clean |
| WIN-1 | Windows/NTFS adapter via JNA + Linux/macOS adapters | windows-filesystem-engineer | ARCH-1 | Complete | 33 platform tests (1 skipped: symlink privilege) |
| CORE-1 | Indexer, activity, policy, analysis, persistence | Lead | ARCH-1 | Complete | Scenario tests pass |
| CORE-2 | Job engine: executor, journal, authorizer, crash recovery | Lead | CORE-1 | Complete | 13 crash-injection tests pass |
| CORE-3 | Engine façade, autonomy, scheduler, watcher, self-test | Lead | CORE-2 | Complete | 21 engine scenario tests pass |
| INT-1 | Real NTFS end-to-end test (mixed folder) | Lead | WIN-1, CORE-3 | Complete | `NativeEndToEndTest` passes |
| BR-1 | Branding (SVG, PNG, ICO) | documentation-engineer (branding) | — | Complete | ICO verified (7 entries) |
| UI-1 | JavaFX application, onboarding, tray, launcher | desktop-ui-engineer | CORE-3 | In progress | Builds; smoke test passes; all views wired |
| UT-1 | Core unit tests & edge cases | unit-test-engineer | CORE-3 | In progress | Real results recorded; defects reported |
| SEC-1 | Security + data-integrity review | security-engineer / data-integrity-engineer | CORE-3 | In progress | Findings triaged; blockers fixed |
| PKG-1 | Windows portable packaging (Temurin runtime + embeddable CPython) | packaging-devops-engineer / Lead | UI-1 | Ready | `IdleStow-cli.exe --smoke-test` and `--self-test` pass on the packaged image |
| BENCH-1 | Indexing + worker benchmarks | Lead | CORE-3 | In progress | Real numbers in `docs/development/TEST_RESULTS.md` |
| DOC-1 | README, user guide, developer guide | documentation-engineer | UI-1 | Pending | Claims match implementation and test results |
| REV-1 | Final independent reviews (architecture, UI, packaging, docs) | code-review-engineer | all | Pending | No open critical/high findings |
