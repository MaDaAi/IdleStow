# Task board

Statuses: Pending · Ready · In progress · Review · Testing · Complete · Blocked

| ID | Description | Area | Depends on | Status | Acceptance criteria |
|---|---|---|---|---|---|
| ARCH-1 | Contracts: platform interfaces, IPC protocol v1, DB schema V1, safety model | Core | — | Complete | Docs in `docs/architecture/`; core compiles |
| PY-1 | Python worker (IPC, analysis, zip/tar.zst, verification, fixtures, benchmarks) | Python worker | ARCH-1 | Complete | 171 pytest pass, ruff clean |
| WIN-1 | Windows/NTFS adapter via JNA + Linux/macOS adapters | Platform | ARCH-1 | Complete | 33 platform tests (1 skipped: symlink privilege) |
| CORE-1 | Indexer, activity, policy, analysis, persistence | Core | ARCH-1 | Complete | Scenario tests pass |
| CORE-2 | Job engine: executor, journal, authorizer, crash recovery | Core | CORE-1 | Complete | 13 crash-injection tests pass |
| CORE-3 | Engine façade, autonomy, scheduler, watcher, self-test | Core | CORE-2 | Complete | 21 engine scenario tests pass |
| INT-1 | Real NTFS end-to-end test (mixed folder) | Core | WIN-1, CORE-3 | Complete | `NativeEndToEndTest` passes |
| BR-1 | Branding (SVG, PNG, ICO) | Branding | — | Complete | ICO verified (7 entries) |
| UI-1 | JavaFX application, onboarding, tray, launcher | UI | CORE-3 | Complete | Builds; smoke test passes; all views wired |
| UT-1 | Core unit tests & edge cases | Testing | CORE-3 | Complete | Real results recorded; defects reported |
| SEC-1 | Security + data-integrity review | Security review | CORE-3 | Complete | Findings triaged; blockers fixed |
| PKG-1 | Windows portable packaging (Temurin runtime + embeddable CPython) | Packaging | UI-1 | Complete | `IdleStow-cli.exe --smoke-test` and `--self-test` pass on the packaged image |
| BENCH-1 | Indexing + worker benchmarks | Core | CORE-3 | Complete | Real numbers in `docs/development/TEST_RESULTS.md` |
| DOC-1 | README, user guide, developer guide | Docs | UI-1 | Complete | Claims match implementation and test results |
| REV-1 | Final independent reviews (architecture, UI, packaging, docs) | Review | all | Complete | No open critical/high findings |
| SEC-FIX | Fix SEC-1 findings 1–8, 10, 12 + regression tests | Core | SEC-1 | Complete | `SecurityRegressionTest` passes |
| PERF-1 | Indexing bottleneck (per-file volume lookups) | Core | BENCH-1 | Complete | 640 → 2 801 files/s at 100k |
| LNX-1 | Make engine scenario tests Linux-compatible | Core | — | Pending | CI green on ubuntu-latest |
| MSI-1 | MSI installer (needs WiX) | Packaging | — | Blocked | MSI built and installs per-user |
| REV-FIX | Fix REV-1 critical #1 (self-test deletion) + #2,#3,#4,#5,#6,#7(lock),#10,#11,#15 | Core | REV-1 | Complete | `SelfTestSafetyTest`; packaged re-verification |
