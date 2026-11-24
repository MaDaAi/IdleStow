# Privacy notice

_Effective: 2026-11-25 · Applies to IdleStow 0.1.0 (desktop application)_

IdleStow runs entirely on your computer. It has no accounts, no servers, no telemetry, no analytics,
no advertising and no AI services. It does not connect to the internet and never sends file names,
file contents or statistics anywhere.

## What IdleStow stores locally, and why

Only on your own computer, in your user profile's data folder (Windows: `%LOCALAPPDATA%\IdleStow`):

| Data | Purpose |
|---|---|
| Paths, names, sizes and timestamps of files in the folders **you** choose to monitor | To find inactive files and show them in the Explorer |
| Activity classifications, recommendations and settings | To explain and remember IdleStow's decisions and your choices |
| Optimization history and recovery records (including SHA-256 checksums of files it changed) | So you can review what happened and undo it |
| Diagnostic logs (rotating, size-limited; may contain file paths, never file contents) | Troubleshooting |
| Recovery manifests (JSON) and, for archive replacement, retained originals inside the monitored folder's `.idlestow` folder | Recovery without the database |

IdleStow reads file contents only to estimate compressibility, compute checksums and create, verify or
restore archives for files you selected or authorized.

## Your control

- Remove a folder in *Settings → Monitoring* to stop indexing it (its index entries are removed; recovery
  records are kept so you can still restore files).
- *Settings → Privacy* lets you export your data, clear the index and clear diagnostic logs.
- Uninstalling IdleStow (deleting its folder) does not delete its data folder; delete
  `%LOCALAPPDATA%\IdleStow` yourself to remove everything. Make sure nothing still needs restoring first.

## Third parties

None receive any data from IdleStow. GitHub (where the source code and releases are hosted) has its own
privacy terms for people who visit or download from it.

## Contact

Questions or concerns: open an issue at https://github.com/MaDaAi/IdleStow (do not include private file
names or data), or use the repository's private security reporting for vulnerabilities.
