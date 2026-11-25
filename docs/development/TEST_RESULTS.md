# Test results

All results below were produced on the development machine on 2026-11-24 / 25. Nothing here is estimated.

**Environment:** Windows 11 Pro 10.0.26100, NTFS (G:, 4 KiB clusters, last-access updates enabled),
AMD Ryzen 5 5500 (12 logical CPUs), 32 GiB RAM, JDK 21.0.1 (build/tests) and Temurin 21.0.13.1+2
(packaged runtime), Python 3.12.6 (dev) / embeddable CPython 3.12.10 (packaged).

## Automated suites

| Suite | Command | Result |
|---|---|---|
| Python worker | `cd python; python -m pytest -q` | **171 passed** (26 tar.zst tests skip when `zstandard` is absent: 145 passed, 26 skipped) |
| Python lint | `cd python; python -m ruff check .` | All checks passed |
| Core (unit, scenario A–K, crash injection, security regressions) | `./mvnw -q -pl core test` | **363 tests, 0 failures, 0 errors, 2 skipped** (file-symlink creation requires a Windows privilege the account lacks; one platform-dependent assumption) |
| Platform (Windows native NTFS, JNA, parsers, real NTFS end-to-end) | `./mvnw -q -pl platform test` | **34 tests, 0 failures, 0 errors, 1 skipped** (symlink privilege; the opt-in benchmark is excluded) |
| App (CSV, prefs, args, coalescer, GUI smoke in a separate JVM) | `./mvnw -q -pl app test` | **22 tests, 0 failures** |

Key test classes: `EngineScenariosTest` (21 acceptance scenarios with the real Python worker),
`CrashRecoveryTest` (13 crash points), `SecurityRegressionTest` (8), `NativeEndToEndTest` (real NTFS:
mixed folder, only inactive files compressed, allocation 4 MB→<2 MB, hashes unchanged, folder compression
attribute unchanged, IdleStow's own reads not counted as activity, decompression), `WindowsNativeTest`.

Mock vs native: core tests use `FakePlatform` (simulated native compression, **mock**); real NTFS
behaviour is verified only by the platform module's `@Tag("native")` tests and the packaged self-test.

## Clean checkout

`git clone . target/clean-clone` then `./mvnw -B verify "-Dsurefire.excludedGroups=gui,benchmark"` (Windows, JDK 21): **415 tests, 0 failures, 0 errors, 3 skipped**, exit 0.

## Final run after the REV-1 fixes

`./mvnw -B -q install` → exit 0: core 363/0 failures/2 skipped, platform 34/0/1, app 22/0/0; pytest 171 passed.
Packaged image rebuilt and re-verified: smoke test indexes a sample folder and finds the bundled worker; self-test passes all six operations; `--self-test` on a non-empty folder is refused and the folder is left intact.

## Packaged application (`dist/windows/IdleStow`)

Run with `PATH=C:\Windows\System32;C:\Windows` (no Java, no Python available):

| Check | Result |
|---|---|
| `IdleStow-cli.exe --smoke-test --data-dir dist\verify-smoke` | `Worker: Running … (Bundled Python worker …)`, `SMOKE OK`, exit 0 |
| `IdleStow-cli.exe --self-test dist\verify-selftest` | native compression PASS, native decompression PASS, ZIP replace PASS, ZIP restore PASS, tar.zst replace PASS, tar.zst restore PASS — `SELF-TEST PASSED`, exit 0 |
| Image size / zip size | 164.5 MiB / 75.6 MiB |
| MSI installer | **Not built** — WiX Toolset not installed |

## Benchmarks

### Indexing + analysis (metadata only, real Windows adapter) — `IndexingBenchmark`

Before optimization (`benchmarks/results/indexing-baseline.md`):

| Files | Index + analysis | Files/s | Unchanged rescan |
|---:|---:|---:|---:|
| 1 000 | 1.43 s | 700 | 1.36 s |
| 10 000 | 15.50 s | 645 | 14.09 s |
| 100 000 | 156.33 s | 640 | 151.74 s |

Profiling showed `WindowsInspector.inspect` at 0.78 ms/file, dominated by a volume-path lookup and
`GetDiskFreeSpaceW` per file. Caching the cluster size per volume serial reduced it to 0.17 ms/file.

After (`benchmarks/results/indexing-optimized.md`):

| Files | Dirs | Index + analysis | Files/s | Unchanged rescan | Explorer page query | Name search | DB size | Heap after |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 000 | 20 | 0.50 s | 2 008 | 0.33 s | 2.2 ms | 1.2 ms | 1.2 MiB | 10 MiB |
| 10 000 | 200 | 5.01 s | 1 998 | 3.31 s | 0.7 ms | 13.3 ms | 10.4 MiB | 6 MiB |
| 100 000 | 2 000 | 35.70 s | 2 801 | 38.79 s | 1.8 ms | 114.4 ms | 103.6 MiB | 6 MiB |

### Database/analysis optimization (schema V2) — `IndexingBenchmark` and `DbPerfBenchmark`

Machine: AMD64 Family 25 Model 80 (12 logical CPUs), max heap 8168 MiB, Windows 11 10.0.26100, Oracle JDK 21.0.1,
synthetic data only. Same-machine before/after, single runs (no repeats; run-to-run noise of roughly +-15 % was
observed on the micro-benchmark).

End to end (`./mvnw -q -pl platform -am test -Dtest=IndexingBenchmark -Dsurefire.failIfNoSpecifiedTests=false
-Dsurefire.excludedGroups= -Dbench.sizes=10000,100000`; includes the filesystem walk and the Windows inspector,
which were not changed):

| Files | Index + analysis before -> after | Unchanged rescan before -> after | DB size before -> after |
|---:|---:|---:|---:|
| 10 000 | 4.03 s -> 3.42 s | 3.93 s -> 2.98 s | 12.6 -> 13.1 MiB |
| 100 000 | 40.73 s -> 28.26 s | 47.74 s -> 23.52 s | 123.8 -> 129.3 MiB |

Database layer only (`./mvnw -pl core test -Dtest=DbPerfBenchmark -Dsurefire.excludedGroups= -Dbench.n=100000`,
100 000 synthetic rows, no filesystem), milliseconds:

| Step | Before | After |
|---|---:|---:|
| initial upsert, 500-row batches | 10 164 | 6 340 - 7 460 (4 runs) |
| unchanged re-upsert | 14 537 | 3 860 - 4 020 (4 runs) |
| `AnalysisService.analyze` | 11 157 | 2 660 - 2 880 (3 runs) |
| `filesPage` full paged walk (1000 rows/page) | 8 278 | 1 050 - 1 160 (3 runs) |
| `replaceForRoot`, 10 000 candidates | 1 027 | 290 - 360 |
| `totals()` after a write / repeat call | 512 / 512 | 134 - 144 / 0.0 |
| `byCategory()` after a write / repeat call | 142 / 142 | 52 - 72 / 0.0 |
| `largestCandidates(10)` | 54 | 0.1 |
| `candidates.list(PENDING, SAVINGS, 200)` | 87 | 2.5 - 2.9 |
| `countFiles()` | 103 | 1.6 - 7.7 |
| `byRoot()`, `byActivity()`, search, `candidates.list(.., IDLE, ..)` | 105, 106, 156, 83 | unchanged within noise |

### Python worker throughput — `python -m idlestow.benchmarking --size-mib 64 --repeat 3`

64 MiB synthetic files (likely served from the OS cache); median of 3 runs
(`benchmarks/results/python-worker-*.json`):

| Operation (compressible text) | fast | balanced |
|---|---:|---:|
| SHA-256 | 1 442 MiB/s | 1 406 MiB/s |
| ZIP create (incl. hashing + fsync) | 87.7 MiB/s, ratio 0.215 | 41.1 MiB/s, ratio 0.109 |
| ZIP verify (full decompression + hash) | 249 MiB/s | 306 MiB/s |
| tar.zst create | 135.8 MiB/s, ratio 0.101 | 51.2 MiB/s, ratio 0.054 |
| Compressibility estimate (4 MiB sample) | 0.33 s/file | 0.42 s/file |

Consequence: the default estimation sample was reduced from 4 MiB to 1 MiB (evenly spaced chunks).

## Not verified

* Windows 10, Linux, macOS (no such machines/CI runs available).
* GitHub Actions workflows (repository not pushed).
* Long-running background scheduling and the system tray menu (manual launch only).

## 2026-11-24: Simple view, Terms of Use, new logo, tray menu, LegalGuard audit

| Command | Result |
|---|---|
| `./mvnw -B install -Dsurefire.excludedGroups=gui,benchmark` | exit 0; 424 Java tests, 0 failures, 0 errors, 3 skipped (symlink privilege / platform) |
| New tests | `LegalTest` (3: acceptance persisted, older version re-asked, bundled TERMS.md version matches code), `PauseStateTest` (1) |
| `cd python; python -m pytest -q` / `python -m ruff check .` | 171 passed / all checks passed |
| `java -jar app/target/idlestow.jar --smoke-test` (builds every Advanced view and the Simple view) | SMOKE OK |
| `scripts/package-windows.ps1`, then `IdleStow-cli.exe --smoke-test` | package built; SMOKE OK with the bundled runtime and worker; `app/` contains only logback 1.5.38 |
| Network sampling during the packaged smoke run (`Get-NetTCPConnection` / `Get-NetUDPEndpoint` for the app process every 300 ms) | 0 endpoints; worker processes too short-lived to sample |
| UI snapshots (dark and light) of the Simple view (empty, ready), Advanced Overview, tray menu, Terms dialog, from synthetic files in `target/snap-files` | Rendered as intended after two CSS fixes (link color, unticked checkbox mark) |

Found and fixed: the local package had shipped logback 1.5.18 next to 1.5.38 because Maven never cleans
`app/target/lib`; `package-windows.ps1` now clears it and refuses duplicate library versions.

## 2026-11-25: 0.1.1 (in-app updater, performance work)

| Command | Result |
|---|---|
| `./mvnw -B install -Dsurefire.excludedGroups=gui,benchmark` | exit 0; 507 Java tests, 0 failures, 0 errors, 3 skipped |
| `cd python; python -m pytest -q` / `python -m ruff check .` | 171 passed / all checks passed |
| Updater unit and architecture tests (`core/update/*Test`) | 76 passed, including `NetworkAccessArchitectureTest` (only `HttpsTransport` may use the network) |
| `scripts/package-windows.ps1` (now runs the packaged `--smoke-test` itself) | package built, SMOKE OK; zip 70,731,907 bytes (0.1.0: 79,389,666) |
| Swap script on fake app folders named `My Apps [x]` with read-only launchers: normal update, second update, failed move, unmarked `.previous` | all four behaved correctly (applied, replaced marked backup, rolled back, refused) |
| Live end-to-end: packaged runtime checked GitHub as 0.1.1 (up to date) and as 0.0.9 (offered v0.1.0), downloaded 79,389,666 bytes, verified SHA-256, unpacked, swapped folders, restarted with `--data-dir` | passed (after two fixes below) |

Found and fixed by the live test:
- Zips made by Windows PowerShell's `Compress-Archive` use backslash entry names, so `ZipEntry.isDirectory()`
  missed directory entries; the unpacker now decides on the normalized name (regression test added).
- jpackage launchers and JVM class-data archives are read-only, which Windows refuses to delete; the updater now
  clears the flag inside folders it owns (regression test added).

Performance (single runs on the development PC, synthetic data; details in the helpers' reports and
`docs/development/build-and-packaging.md`): 100k-file index + analysis 40.7 s to 28.3 s; unchanged rescan
47.7 s to 23.5 s; window start about 2.2 s to 1.8 s; worker start about 260 ms to 175 ms; packaged smoke start
3.35 s to 2.22 s (median).
