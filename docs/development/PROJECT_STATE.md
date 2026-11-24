# Project state

_Last updated: 2026-11-25_

## Current milestone

**0.1.0 release candidate**: feature-complete for Windows/NTFS and verified (see TEST_RESULTS.md). It
is not published: pushing to GitHub needs the owner's authorization.

## Architecture (summary)

Modular Java 21 monolith (`core`, `platform`, `app`) plus a persistent Python 3.12 worker over JSON-lines
stdio IPC (protocol v1). SQLite persistence (WAL, synchronous=FULL). See `docs/architecture/overview.md`.

## Completed modules

- **core**
  - Indexer: scanner, watcher, rename/move detection.
  - Activity classifier.
  - Policy engine and protections.
  - Analysis/recommendations.
  - Job engine: executor, journal, authorizer, crash recovery, no-replace moves.
  - Engine façade: autonomy, scheduler, self-test.
- **platform**: Windows/NTFS via JNA (verified). Linux and macOS adapters are implemented but not verified.
- **python**:
  - Worker IPC.
  - Estimation (sampling, entropy, NTFS/zip/zstd).
  - zip/tar.zst create, verify, extract and inspect, with security guards.
  - Fixtures, benchmarks.
- **app**:
  - JavaFX UI: 7 views, plan preview, onboarding, tray, themes.
  - Launcher modes.
- **Packaging**: portable Windows image with pinned Temurin runtime and embeddable CPython, verified
  without system Java/Python.
- **Docs**: architecture, ADRs, safety and threat model, recovery guide, README, community files, CI workflows.

## Incomplete / limitations

See `KNOWN_ISSUES.md`. Main items:
- No MSI installer (WiX not installed).
- Linux and macOS unverified.
- CI never run.
- No automated GUI interaction tests beyond the smoke test.

## Recent decisions

- Java 21 LTS, because JDK 25 is unavailable (ADR 0002).
- Temurin runtime bundled for redistributability. The local Oracle JDK is not shipped.
- Security-review fixes:
  - no-replace renames;
  - retained-file protection;
  - per-file autonomous re-check;
  - reviewed-snapshot binding;
  - record path validation.
- Cluster-size cache: indexing 4.3× faster at 100k files.
- Default estimation sample 1 MiB (from benchmark data).
- Final review fixes: self-test can never delete pre-existing data; honest smoke test; hash-pinned worker wheel; redistributable-JDK check; WiX 3 detection; CSV formula guard; temp-space limit enforced.

## Build / test status

- Build: `./mvnw package` succeeds.
- Tests:
  - Python 171/171 passed.
  - Core 363 (0 failures, 2 skipped).
  - Platform 34 (0 failures, 1 skipped).
  - App 22 (0 failures).
- Packaged smoke test and self-test passed.

## Blockers

- Publishing to GitHub needs the owner's go-ahead.
- Installer builds need WiX.

## Next actions

1. Owner: create the GitHub repository `MaDaAi/IdleStow`, push, and check that CI passes on Windows, Linux and macOS.
2. Adapt the engine scenario tests for Linux (clock offset instead of creation times), see KI-1.
3. Install WiX on a build machine to produce an MSI.
4. Add GUI interaction tests (TestFX + Monocle).
5. Native compression adapters for Btrfs and APFS (planned).
