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
| UI-1 | JavaFX application, onboarding, tray, launcher | desktop-ui-engineer | CORE-3 | Complete | Builds; smoke test passes; all views wired |
| UT-1 | Core unit tests & edge cases | unit-test-engineer | CORE-3 | Complete | Real results recorded; defects reported |
| SEC-1 | Security + data-integrity review | security-engineer / data-integrity-engineer | CORE-3 | Complete | Findings triaged; blockers fixed |
| PKG-1 | Windows portable packaging (Temurin runtime + embeddable CPython) | packaging-devops-engineer / Lead | UI-1 | Complete | `IdleStow-cli.exe --smoke-test` and `--self-test` pass on the packaged image |
| BENCH-1 | Indexing + worker benchmarks | Lead | CORE-3 | Complete | Real numbers in `docs/development/TEST_RESULTS.md` |
| DOC-1 | README, user guide, developer guide | documentation-engineer | UI-1 | Complete | Claims match implementation and test results |
| REV-1 | Final independent reviews (architecture, UI, packaging, docs) | code-review-engineer | all | Complete | No open critical/high findings |
| SEC-FIX | Fix SEC-1 findings 1–8, 10, 12 + regression tests | Lead | SEC-1 | Complete | `SecurityRegressionTest` passes |
| PERF-1 | Indexing bottleneck (per-file volume lookups) | Lead | BENCH-1 | Complete | 640 → 2 801 files/s at 100k |
| LNX-1 | Make engine scenario tests Linux-compatible | java-core-engineer | — | Pending | CI green on ubuntu-latest |
| MSI-1 | MSI installer (needs WiX) | packaging-devops-engineer | — | Blocked | MSI built and installs per-user |
| REV-FIX | Fix REV-1 critical #1 (self-test deletion) + #2,#3,#4,#5,#6,#7(lock),#10,#11,#15 | Lead | REV-1 | Complete | `SelfTestSafetyTest`; packaged re-verification |
