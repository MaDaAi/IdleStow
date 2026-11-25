# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.

## [0.1.1] - Unreleased

### Added
- In-app updater, opt-in. On first run IdleStow asks whether to check for updates automatically; closing the
  question counts as no. When on, it makes at most one request a day to GitHub's API for the latest release.
  A newer version shows a banner, a tray notification and a dialog with the release notes. Installing downloads
  the release, checks its SHA-256 against the published checksum file, and restarts into the new version. It is
  refused while jobs are running and works for the portable package only. The updater is the only part of
  IdleStow that uses the internet. See the Updates section of `README.md`.

### Changed
- Performance improvements (details to be added).

## [0.1.0] - Unreleased

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
