# Getting started

This guide covers installing IdleStow, the first-run setup, and what each screen and setting does.
For undoing an optimization see [Recovery](recovery.md). For errors see [Troubleshooting](troubleshooting.md).

## Requirements

* **Windows 11 with NTFS** is the supported and tested configuration. Windows 10 is implemented but not verified.
  Linux and macOS are not supported for use yet. See [platform compatibility](../platform-compatibility.md).
* Java and Python are **bundled**. You do not install them.
* IdleStow needs no account. It works without a network connection. The only thing that uses the internet is the
  optional update check (see [Updates](#updates)).

## Install

1. Download `IdleStow-0.1.0-windows-x64-portable.zip`, or build it yourself (see
   [build and packaging](../development/build-and-packaging.md)).
2. Extract the zip to any folder you can write to.
3. Run `IdleStow\IdleStow.exe`.

The portable package does not install anything system-wide and creates no shortcuts. Its only optional
system change is the **Start at login** entry (see [Background and tray](#background-and-tray)).

### Uninstall

1. In **Settings > Automation**, turn off **Start IdleStow when I sign in** if you enabled it.
2. Delete the `IdleStow` folder.
3. Optionally delete IdleStow's own data folder, `%LOCALAPPDATA%\IdleStow`. It holds the index, history,
   settings, logs and recovery manifests.

Uninstalling never removes your files, your archives or retained originals. Before you delete the data
folder, restore any archived files you still need from the **Recovery** screen, because the database
there is what lists them.

## First run

1. **Terms of Use.** The first time IdleStow starts it shows a short summary of the [Terms of Use](../../TERMS.md)
   and the [Privacy notice](../../PRIVACY.md). Nothing is opened, read or changed until you tick the box and choose
   **Agree and continue**. **Exit** closes IdleStow without changing anything. You are asked again only if the
   terms change.
2. **Simple view.** IdleStow then opens its Simple view.
3. **Update question.** When the window first appears, IdleStow asks once: *Check for updates automatically?* Choose
   **Yes, check automatically** or **No, I'll check myself**. Closing the question counts as No. See [Updates](#updates).

## The Simple view

One screen with the essentials:

* **One status card** says what to do next: *Choose a folder*, *Looking through your files...*,
  *You can free up about X* with a **Free up space** button, *Freeing up space...*, or *All tidy*.
* **Free up space** confirms first, then shrinks the suggested files with transparent (NTFS) compression only.
  Files keep their names and places and open normally. Archive methods are only offered in the Advanced view.
* **Three numbers:** space saved (measured), files checked, folders watched.
* **Your folders:** add a folder, or stop watching one (your files are not touched).
* **Undo changes** opens Recovery. **Pause** stops all work until you resume.
* **Advanced view** (top right) shows everything described in the rest of this guide. In the Advanced view,
  **Simple view** at the bottom of the sidebar switches back. IdleStow remembers your choice.

## The setup wizard (Advanced view)

If you switched to the Advanced view before finishing setup, the wizard opens when IdleStow starts. Nothing is
selected or changed until you choose it.

| Step | What you choose |
|---|---|
| 1. Welcome | Read the safety summary. |
| 2. Choose folders | Add the folders IdleStow may look at. Nothing is preselected. |
| 3. When is a file idle? | The idle threshold: 7, 14, 30 (default), 60, 90, 180 or 365 days. |
| 4. How should files be compressed? | Preferred method: transparent native compression (default) or a ZIP compressed copy. |
| 5. Who decides? | Manual, Ask before optimizing (default), or Fully autonomous. Autonomous asks for folder selection here. |
| 6. Background | Keep scanning in the background, and start at login. Both are off by default. |
| 7. First scan | A read-only scan of your folders. Cancel is available. |
| 8. Results | File count, recommendations, and estimated savings. |

Press **Go to Overview** at the end.

* **Skip for now** closes the wizard. It is offered again the next time IdleStow starts, because setup is
  not marked complete.
* Choosing **Fully autonomous** does not activate it. IdleStow shows the exact autonomous policy after the
  first scan and only enables it when you confirm. See [Authorization modes](#authorization-modes).

## Folders

IdleStow only reads and changes files inside folders you authorize.

* Add folders from the setup wizard, from **Settings > Monitoring > Add folder...**, from the Overview's
  **Add a folder** button, or from Explorer.
* Each folder shows its file system, whether native compression is supported on it, and its
  last-access tracking. Select the folder in **Settings > Monitoring** to see these details.
* Folders cannot overlap. A folder inside another authorized folder, or the reverse, is refused.
* Some locations are always refused: Windows and program folders, `AppData`, and IdleStow's own data
  folder. See [Troubleshooting](troubleshooting.md#this-folder-cannot-be-monitored).
* **Revoke / re-authorize** stops all work on a folder immediately. Existing compressed files stay as they
  are and remain restorable.
* **Remove** deletes only IdleStow's index of the folder. Your files, archives and recovery records stay where
  they are.
* Per folder you can allow autonomous optimization and allow archives (ZIP and tar.zst). Both are off by
  default and are set under **Settings > Monitoring**.

## Activity and confidence

IdleStow never treats an old modification date as proof that a file is unused. It combines evidence:
modification and creation times, changes it has seen, files opened through IdleStow, last-access times
where the file system reliably records them, and what you tell it. Its own reads are ignored. Newly
indexed files go through an observation period (14 days by default) before IdleStow trusts inactivity.

Every file gets one activity class:

| Class | Meaning |
|---|---|
| Active | Used recently. Never recommended. |
| Probably active | Some recent evidence of use. Never recommended. |
| Potentially inactive | Idle by the evidence, but with medium confidence. |
| Inactive (reliable evidence) | Idle by reliable evidence, such as a confirmation or reliable access times. |
| Activity unknown | IdleStow cannot tell. It leaves the file alone. |
| Pinned | You pinned it. It is never changed. |
| Excluded | You excluded it, or it is protected. It is never changed. |

Each classification has a confidence: **None, Low, Medium or High**. Recommendations need at least the
**minimum confidence to recommend** (Medium by default). Autonomous optimization needs the **minimum
confidence for autonomy** (High by default, never below Medium).

Explorer shows the reasons for each file in its details pane. In the context menu you can also tell
IdleStow your own judgement:

* **Mark as active** records that the file is in use.
* **Confirm inactive** records that the file is not in use.

Where a volume does not record last-access times, IdleStow cannot see reads, so many files show
**Activity unknown** until you confirm them. This is expected behaviour, not a fault.

## Authorization modes

Set the mode under **Settings > Automation**.

| Mode | What happens |
|---|---|
| **Manual** | IdleStow only recommends. You start every optimization yourself, after a preview. |
| **Ask before optimizing** (default) | Recommendations wait for your approval. You approve, then IdleStow runs them. |
| **Fully autonomous** | IdleStow optimizes eligible files by itself, within a policy you confirm. |

### How autonomous mode is confirmed

Autonomous mode is off until you confirm its exact policy in a dialog titled **Enable autonomous mode?**.
The dialog lists:

* the folders where autonomy is allowed (you must allow at least one folder first),
* the methods IdleStow may use (only reversible transparent native compression),
* the timing rules: inactivity threshold, minimum confidence and observation period,
* the resource limits: CPU, idle-only, battery, and maximum files per pass,
* what is never touched: system and program files, cloud-synchronized folders, pinned and excluded items,
  files in use, and anything outside the allowed folders,
* the recovery limits, and how to stop it.

IdleStow stores a digest of the confirmed policy. If any value in that policy changes, autonomy switches
off until you confirm again. Archive replacement is never done autonomously.

## Screens

Use the sidebar, or **Ctrl+1** to **Ctrl+7** in sidebar order. **F5** starts a scan. **Ctrl+F** moves focus
to the search box where a screen has one.

| Screen | What it is for |
|---|---|
| **Overview** | Totals and charts: storage by category and by folder, activity distribution. Tiles show reclaimed space (measured), potential savings (an estimate), space after retention, indexed files, active jobs and items needing attention. |
| **Explorer** | Browse the authorized folders. Columns show name, type, last activity, confidence and status. Use **Optimize...** on selected files or folders. Right-click for Open, Open containing folder, Copy path, Optimize, Decompress, Restore, Inspect activity, Refresh, Pin or unpin, Exclude, Mark as active, Confirm inactive, and View history. |
| **Recommendations** | Files IdleStow suggests optimizing, grouped by folder. Filter by status and location. Actions: **Approve...**, **Approve all shown...**, **Reject**, **Dismiss**, **Postpone** (1, 7 or 30 days), **Exclude** and **Pin**. In Manual mode the button reads **Review & optimize...**. |
| **Jobs** | Running and finished jobs with state, progress and throughput. Actions: Pause, Resume, Cancel, Inspect (per-file journal), Retry, and Open Recovery. |
| **History** | Every completed action, with method, outcome, origin, mode and measured savings. Filter by path. Export CSV or JSON. |
| **Recovery** | Everything that can be undone. See [Recovery](recovery.md). |
| **Settings** | All settings, in categories. See [Settings](#settings). |

### Review before optimizing (plan preview)

Nothing changes until you approve a plan. The preview shows:

* **Will change**: each file IdleStow will optimize, with its activity and confidence. Uncheck any file to
  leave it out.
* **Will remain untouched**: each excluded file, with the reason.
* the **method** (you can switch methods; unavailable ones show why), the estimated savings, and warnings.
* **How to undo**, for the chosen method.

The **Optimize** button starts the job only after this review. Every file is checked again immediately before
it is changed.

### Methods and what they save

| Method | Space saved | Access | Undo |
|---|---|---|---|
| Transparent native compression (NTFS) | Measured from the file's real allocation | The file keeps its path and opens normally | Decompress |
| ZIP or tar.zst compressed copy | **None**. The copy uses extra space and IdleStow says so | Original untouched | Delete the copy |
| Archive replacement (ZIP or tar.zst) | Only after the retention period | The file must be restored before programs can open it | Restore |

Files that are already compressed (for example JPEG, MP4 or ZIP) are detected by sampling their content, not
only by extension. If compression would not save at least one cluster, IdleStow reverts it and records "No
benefit".

Archive replacement needs your explicit confirmation each time. Replaced originals are kept in a retained
folder for the retention period (14 days by default) before any space is reclaimed.

## Recovery

Every optimization can be undone from the **Recovery** screen, or from **History** and **Explorer**. The actions depend
on how the file was optimized:

* **Decompress** reverses transparent native compression.
* **Restore in place...** puts an archived file back at its original path. **Restore to another folder...** restores it
  elsewhere. An archive copy can be extracted with **Extract to another folder...**.
* **Verify integrity** checks the archive or retained original against its record again.
* **Reclaim space now...** removes a retained original after its archive has been verified. After this the archive is the
  only copy.
* **Delete compressed copy...** removes a compressed copy you no longer want.
* **Open manifest** shows the record IdleStow keeps for the file.

Restores never overwrite an existing file. The full details, including what happens without IdleStow, are in
[Recovery](recovery.md).

## Settings

Settings are grouped into categories. Defaults are shown in the table. Values that are out of range fall back
to the default.

| Category | Setting | Default |
|---|---|---|
| General | Theme | Dark (or Light) |
| General | When the window is closed | Exit IdleStow |
| General | Notify when jobs finish or fail | On |
| General | Check for updates automatically (once a day) | Set by your answer to the first-run question. Closing it counts as No. |
| Monitoring | Rescan interval (used only with background operation) | Every 6 hours |
| Monitoring | Watch folders for changes (applies with background operation) | On |
| Monitoring | Exclusions (path, glob, extension, size, category, volume) | None |
| Optimization | Inactivity threshold | 30 days (presets 7 to 365, or custom) |
| Optimization | Observation period | 14 days |
| Optimization | Minimum confidence to recommend | Medium |
| Optimization | Minimum confidence for autonomy | High |
| Optimization | Minimum savings | 1 MiB |
| Optimization | Minimum savings (%) | 10 % |
| Optimization | Minimum file size | 64 KiB |
| Optimization | Maximum file size | 0 (no limit) |
| Optimization | Compression profile | Balanced (Fast, Balanced, Maximum) |
| Optimization | Preferred method | Transparent native compression |
| Optimization | Archive replacement | Off |
| Optimization, Recovery | Keep originals for | 14 days |
| Automation | Mode | Ask before optimizing |
| Automation | Scan and optimize in the background | Off |
| Automation | Start IdleStow when I sign in | Off |
| Automation | Autonomous pass (hours between passes) | 24 |
| Automation | Only when I am idle | Off (idle for at least 10 minutes when on) |
| Automation | Also work while on battery power | Off |
| Automation | Maximum CPU use | 50 % |
| Performance | Mode (Silent, Balanced, Performance) | Balanced |
| Performance | Parallel jobs | 1 (takes effect after a restart; 1 to 4) |
| Performance | Compressibility sample | 1 MiB |
| Performance | Keep free space | 2 GiB |
| Performance | Files per batch | 500 |
| Privacy | Export my data, clear the file index, clear diagnostic logs | Manual actions |
| Advanced | Verbose logging | Off |

**Keep free space** is a safety margin: IdleStow does not start work that would leave less free space than
this on the volume.

## Background and tray

With **Scan and optimize in the background** off (the default), IdleStow only works while its window is
open, and only when you ask it to.

With it on:

* IdleStow rescans on the schedule you set, and watches folders for changes if **Watch folders** is on.
* Autonomous passes run, if autonomous mode is active and confirmed.
* Work waits when it would run on battery (unless allowed), when the CPU is busier than the limit, when you
  are not idle (if idle-only is on), when a recovery item needs your attention, and when you have paused it.

The **Pause** button in the toolbar pauses optimization and background work until you press **Resume**.
Settings > Automation also offers pauses of 1 hour, 4 hours, or until you resume.

**When the window is closed**:

* **Exit IdleStow** (default) quits the application. If jobs are running, IdleStow asks first. Jobs stop at
  the next safe point, and anything interrupted is checked and recovered when IdleStow next starts.
* **Keep running in the tray** hides the window and keeps IdleStow running in the notification area. This
  choice is only available when the system tray is available. Left-click the tray icon to open
  IdleStow. Right-click it for a small menu that shows the current status (for example *Watching 2 folders* or
  *Paused until 3:40 PM*) and offers **Open IdleStow**, **Pause for 1 hour**, **Pause until I resume** (or
  **Resume** while paused), **Scan now** and **Exit IdleStow**. Press Esc or click elsewhere to close it.

Starting IdleStow with `--background` opens only the tray icon, if the setup is complete and a tray is
available. Otherwise the window opens.

**Start IdleStow when I sign in** adds a per-user startup entry (`HKCU\...\Run`). It needs no administrator
rights, and it runs IdleStow with `--background`.

Long-running background scheduling and the tray menu have only been checked with manual launches. See
[Known issues](../development/KNOWN_ISSUES.md) and [Testing](../development/testing.md).

## Read-only mode

Start IdleStow with `--read-only` to index and recommend without ever changing a file. A banner says so. There
is no setting for this; start IdleStow without the flag to return to normal operation.

## Updates

IdleStow can check for new versions. This is the only part of IdleStow that uses the internet.

* **Settings > General > Updates** shows your version and when IdleStow last checked. Turn on or off
  **Check for updates automatically (once a day)** at any time. **Check for updates** makes one request on demand.
* **If yes:** IdleStow makes at most one request a day to GitHub's API (`api.github.com`) for the latest release.
  The request contains a generic program name with the version, and what any web request contains, such as your IP
  address. Nothing else is sent. If no newer version exists, nothing else happens.
* **If a newer version exists:** a banner, *IdleStow X is available*, appears above the Simple and Advanced views,
  with **See what's new** and **Later**. A tray notification also appears. The dialog shows the release notes and
  offers **Install update**, **Later** and **Skip this version**. Skipping stops automatic checks from announcing
  that version again. A manual check still shows it.
* **Install update** is refused while jobs are running. Wait for them to finish, or pause them first. IdleStow then
  downloads the release from GitHub and checks it before anything changes. It asks **Restart now**, closes, swaps in
  the new version and starts it. If anything fails, the previous version is put back and started.
* Automatic installation works only for the portable package, when the folder containing it is writable (not, for
  example, `Program Files`), and not in read-only mode. Otherwise the dialog offers **Open download page**.
* Your data folder (`%LOCALAPPDATA%\IdleStow`) and your files are never touched by an update.
* For problems and rolling back, see [Troubleshooting updates](troubleshooting.md#troubleshooting-updates).

## Privacy

* IdleStow has no accounts, no telemetry and no cloud service. The only network request is the optional update
  check. It never sends file names, file contents or statistics.
* Indexing reads file names, sizes and timestamps, never file contents. Analysis reads a sample of a
  candidate's content to estimate savings, and an optimization reads the files it changes to verify them.
* Data lives in `%LOCALAPPDATA%\IdleStow`: the database (index, history, settings, recovery records), logs,
  and recovery manifests.
* Logs contain file paths but never file contents. See [Troubleshooting](troubleshooting.md#where-the-logs-are).
* **Settings > Privacy** lets you open the data folder, export your data as JSON (`idlestow-export.json`),
  clear the file index (rebuilt by the next scan, nothing else is lost), and delete old diagnostic logs.
