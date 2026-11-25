# Privacy notice

_Effective: 2026-11-25 · Applies to IdleStow 0.1.1 and later (desktop application)_

> Draft written by the developer; not reviewed by a lawyer. It describes what the software actually does, which
> was checked in the source code and tests (see `docs/compliance/DATA_INVENTORY.md`).

IdleStow runs entirely on your computer. It has no accounts, no servers, no telemetry, no analytics,
no advertising and no AI services. It never sends file names, file contents or statistics anywhere.
Its only use of the internet is checking GitHub for a newer version of IdleStow, and only if you allow it
(see [Update checks](#update-checks-the-only-internet-use)).

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

## Update checks (the only internet use)

The first time IdleStow starts, it asks whether it may check for updates automatically.

- **If you say yes,** IdleStow sends one request about once a day to GitHub (`api.github.com`) asking for
  the latest IdleStow version. The request contains only what any web request contains, including your IP
  address and a generic program name with IdleStow's version number. It contains nothing about you, your files or
  your settings. If no newer version exists, nothing else happens.
- **If you say no** (or close the question), IdleStow never connects by itself. You can still choose
  *Settings → General → Updates → Check for updates*, which makes the same single request once.
- **Downloading an update** only happens when you choose *Install update*. IdleStow then downloads that
  release's files from GitHub (`github.com` and GitHub's download servers), checks them against the release's
  published checksums, and installs them. Your settings, history and recovery records are kept.

GitHub, Inc. receives these requests under its own privacy statement:
https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement. IdleStow stores
only the time of the last check and your choice, in `ui-state.json` in its data folder. Turn automatic checks
off at any time in *Settings → General → Updates*.

## Your control

- Remove a folder in *Settings → Monitoring* to stop indexing it (its index entries are removed; recovery
  records are kept so you can still restore files).
- *Settings → Privacy* lets you export your data, clear the index and clear diagnostic logs.
- Uninstalling IdleStow (deleting its folder) does not delete its data folder; delete
  `%LOCALAPPDATA%\IdleStow` yourself to remove everything. Make sure nothing still needs restoring first.

## What IdleStow does not do

- It does not connect to the internet except for the update check and download you allow (above).
- It does not use cookies, advertising identifiers, analytics or crash-reporting services.
- It does not use your camera, microphone, location or screen.
- It does not knowingly collect information about anyone, including children. It is a general-purpose file
  utility and stores only file metadata on your own computer.

## Third parties

No third party receives data about you or your files from IdleStow. The only connection is the update
check and download described above, which goes to GitHub.

## Changes to this notice

If IdleStow's data handling ever changes, this notice will be updated before that version is released, and
the effective date above will change. Any new network connection or data sharing would be clearly announced
and would require your agreement, as the update check does.

## Who is responsible

IdleStow is a personal open-source project by Mansoor Alakoozai. Because the app never sends data to the
developer, the developer does not receive, hold or process your personal information.

## Contact

Questions or concerns: open an issue at https://github.com/MaDaAi/IdleStow (do not include private file
names or data), or use the repository's private security reporting for vulnerabilities.
