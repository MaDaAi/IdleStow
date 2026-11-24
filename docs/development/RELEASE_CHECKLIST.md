# Release checklist (definition of done) — 0.1.0

Legend: ✅ done and verified · ⚠️ done with a stated limitation · ❌ not done

| Requirement | Status | Evidence |
|---|---|---|
| Clean checkout builds | ✅ | Fresh `git clone` + `./mvnw -B verify -Dsurefire.excludedGroups=gui,benchmark`: 415 tests, 0 failures, 3 skipped |
| Desktop application launches | ✅ | GUI smoke test (`app` `SmokeTest`), manual launch, packaged `--smoke-test` |
| Java primary, Python meaningful | ✅ | Core/platform/app in Java; worker does estimation, archives, hashing/verification, fixtures, benchmarks |
| Java/Python IPC works | ✅ | pytest end-to-end subprocess test; `WorkerClientRobustnessTest`; engine scenario tests use the real worker |
| SQLite persistence, migrations, health checks | ✅ | `DatabaseTest` |
| First-run onboarding | ⚠️ | Implemented; only the first wizard screen is exercised automatically |
| Folder authorization, read-only indexing, incremental indexing | ✅ | `EngineScenariosTest`, `ScannerTest` |
| Explorer, search, filters | ⚠️ | Implemented and smoke-loaded; queries unit/benchmark-tested; no automated GUI interaction tests |
| Activity classification; unknown handled conservatively | ✅ | `ActivityClassifierTest` (48), scenario K |
| Mixed-activity folders | ✅ | Scenario B, folder preview test, real NTFS `NativeEndToEndTest` |
| Recommendations, approval-based and manual optimization | ✅ | Scenarios A–C |
| Autonomous optimization within policy; revocation | ✅ | Scenarios E/F, `SecurityRegressionTest` |
| Native NTFS compression + decompression on a real volume | ✅ | `NativeEndToEndTest`, `WindowsNativeTest`, packaged self-test |
| Archive creation, verification, safe restoration | ✅ | Scenario G, pytest archive security tests, packaged self-test |
| Accurate compression statistics | ⚠️ | Measured from allocation; see KNOWN_ISSUES KI-4 |
| History, job management, pause/cancel | ⚠️ | Engine-level tested; UI controls not automatically tested |
| Background indexing / scheduler | ⚠️ | Implemented; scheduler intervals not covered by an automated long-running test |
| Recovery handling (crash injection) | ✅ | `CrashRecoveryTest` (13 cases) |
| Settings persist; themes | ⚠️ | `SettingsTest` (49); theme switching smoke-loaded only |
| Protected paths respected | ✅ | `PolicyEngineTest` (73), `AuthorizerTest` |
| Security review completed, blockers fixed | ✅ | SEC-1 review; fixes + `SecurityRegressionTest` |
| Performance benchmarks recorded | ✅ | `benchmarks/results/` |
| Windows portable packaging, runtime included | ✅ | Packaged image verified with no Java/Python on PATH |
| Installer packaging | ❌ | WiX not installed (KNOWN_ISSUES KI-5) |
| README, docs, GitHub workflows, license | ✅ | Repository root, `docs/`, `.github/` |
| CI status on GitHub | ❌ | Not run: the repository has not been pushed (requires the owner's authorization) |
| Linux / macOS verification | ❌ | Not verified (KNOWN_ISSUES KI-1) |
