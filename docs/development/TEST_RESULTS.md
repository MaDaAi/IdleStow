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
