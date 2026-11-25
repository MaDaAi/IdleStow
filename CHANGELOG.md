# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.

## [0.1.2] - 2026-11-25

### Fixed
- The "Check for updates automatically?" question stretched wider than the screen; it now opens at a normal
  size with shorter wording. The "Update available" dialog has a fixed width too.

## [0.1.1] - 2026-11-25

### Added
- In-app updater, opt-in. On first run IdleStow asks whether to check for updates automatically; closing the
  question counts as no. When on, it makes at most one request a day to GitHub's API for the latest release.
  A newer version shows a banner, a tray notification and a dialog with the release notes. Installing downloads
  the release, checks its SHA-256 against the published checksum file, and restarts into the new version. It is
  refused while jobs are running and works for the portable package only. The updater is the only part of
  IdleStow that uses the internet. See the Updates section of `README.md`.

### Changed
- Faster indexing and analysis: 100,000 synthetic files index in 28.3 s instead of 40.7 s, and an unchanged
  rescan takes 23.5 s instead of 47.7 s (single benchmark runs on the development PC). New database indexes
  (migration V2) and cached dashboard totals make the Overview and Simple view cheap to refresh.
- Lighter UI: the Simple view and Overview only redraw what changed, the tray menu and status bar no longer
  query the database on the UI thread or while hidden, and the engine starts while the window opens
  (first window about 1.8 s instead of 2.2 s).
- Python worker starts in about 175 ms instead of about 260 ms; tar.zst archives are created 30-55% faster.
- Smaller, faster package: the download is about 71 MB instead of 79 MB (trimmed Java runtime and native
  libraries for other operating systems removed), and the packaged app starts in about 2.2 s instead of
  3.4 s (class-data sharing and a lighter garbage collector).

## [0.1.0] - 2026-11-25

### Added
- Simple view (now the default): one screen with a single *Free up space* action that uses transparent
  compression only, plain-language status, folder list and an *Undo changes* shortcut. The previous interface
  is the Advanced view; switch either way at any time.
- Terms of Use (`TERMS.md`) with a first-run agreement screen; acceptance is versioned and asked again when the
  terms change. Terms, privacy notice, license and third-party notices are viewable in the app.
- New logo (document with a crescent moon on a navy tile) for the app, window, tray and Windows icon; the
  theme accent colors follow it.
- Themed tray menu showing the current status, with *Pause for 1 hour*, *Pause until I resume* / *Resume*,
  *Scan now* and *Exit*; a left click opens IdleStow.
- Persistent metadata-only indexer with rename/move detection, reconciliation and filesystem watching.
- Evidence-based activity classification with explanations, observation period and self-read filtering.
- Policy engine with mandatory protections, pins, exclusions and scoped policies.
- Recommendations with per-file eligibility in mixed-activity folders and sampled savings estimates.
- Transparent NTFS compression (Windows) with handle-based identity checks and SHA-256 verification.
- ZIP and tar.zst archive copy/replacement via the Python worker with full verification, retention of
  originals, and restore.
- Durable job engine with operation journal and crash recovery.
- Manual, Ask-before-optimizing and Fully-autonomous modes with plan-bound approvals and confirmed
  autonomy policies.
- JavaFX desktop application (dashboard, explorer, recommendations, jobs, history, recovery, settings,
  onboarding, tray, dark/light themes).
- Windows portable packaging with bundled Java runtime and Python worker.
