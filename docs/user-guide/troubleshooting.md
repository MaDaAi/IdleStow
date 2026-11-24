# Troubleshooting

Each entry gives the message or symptom, what causes it, and what to do. Messages are quoted as IdleStow shows
them. Some are shortened.

For undoing an optimization, see [Recovery](recovery.md).

## Optimization worker unavailable

**Message:** *"Optimization worker unavailable."* (banner at the top of the window), or *"The IdleStow Python worker
was not found."*

**Cause:** IdleStow runs compression and verification in a bundled Python worker. The worker folder is missing
or incomplete. Indexing and recommendations still work, but compression, verification and archive methods are
disabled.

**What to do:**

1. Extract the portable zip again, keeping the whole `IdleStow` folder structure. The worker lives in
   `IdleStow\worker\`, next to `IdleStow\app\`.
2. Do not move `IdleStow.exe` out of its folder.
3. Restart IdleStow.

If a worker starts but then fails repeatedly, IdleStow reports *"The Python worker crashed repeatedly and was not
restarted."* Check the logs (see [Where the logs are](#where-the-logs-are)). Look for lines from the `idlestow.worker`
logger.

## This folder cannot be monitored

**Message:** *"This folder cannot be monitored: Protected system or application location (...)"*, or *"Inside a
protected folder type ('...')"*, or *"This folder contains IdleStow's own data directory and cannot be monitored"*.

**Cause:** IdleStow never works inside these locations, whatever you choose:

* Windows and program folders: `Windows`, `Program Files`, `ProgramData`, `System Volume Information`, `$Recycle.Bin`,
  `Recovery`, `Boot`, and the `Packages` and `Microsoft` folders under `AppData`.
* Any folder under `AppData`, `Library`, `.config`, `.local` or `.cache` in your user profile. A path component
  named `AppData` is refused wherever it appears.
* Development and tool folders: `.git`, `.svn`, `.hg`, `node_modules`, `.venv`, `venv`, `__pycache__`, `.idea`,
  `.vscode`, `.gradle`, and similar.
* Game libraries (`steamapps`, `Epic Games`, `GOG Games`, `Riot Games`, `XboxGames`), container and VM folders
  (`.docker`, `Virtualbox VMs`, `.vagrant`), cloud-sync internals (`.dropbox`, `.sync`, `.stfolder` and similar),
  and `.idlestow`.
* IdleStow's own data folder (`%LOCALAPPDATA%\IdleStow` by default).

**What to do:** choose a folder that is outside these locations, such as a folder in `Documents` or a data drive.
Archive replacement is refused for files in cloud-synchronized folders, and cloud placeholders are never modified.

## This folder overlaps the monitored folder

**Message:** *"This folder overlaps the monitored folder ... Overlapping folders are not allowed."*

**Cause:** one folder is inside another, or contains another. IdleStow refuses both, so that each file belongs to
exactly one authorized folder.

**What to do:** add only the outer or only the inner folder. To change which one you keep, remove the other first in
**Settings > Monitoring**. Removing a folder only deletes IdleStow's index of it.

## Native compression is not offered

**Symptom:** the plan preview shows *"Transparent native compression (unavailable: ...)"*, or a folder shows *"Native
compression: not supported"* in **Settings > Monitoring**.

**Cause:** the volume does not meet the requirements. IdleStow shows the exact reason in the folder details:

* the volume is not NTFS (for example FAT, exFAT or ReFS),
* the volume is a network volume,
* the volume is read-only,
* the volume does not support file compression,
* the cluster size is larger than 4 KiB,
* the cluster size could not be determined.

The Advanced page also lists each folder's file system, cluster size and whether native compression is available.

**What to do:** use an NTFS volume with 4 KiB clusters for native compression. Archive methods (ZIP and tar.zst) are not
limited by this, but they still need the worker.

## Activity shows "Unknown" or "Activity unknown"

**Symptom:** many files show *Activity unknown*. The explanation says that last-access tracking is unavailable on the
volume, so activity cannot be determined confidently.

**Cause:** IdleStow treats a file as inactive only when it has reliable evidence. On Windows, the **Last-access tracking**
line of a folder shows whether NTFS last-access times are recorded. If the system has last-access updates disabled
(`NtfsDisableLastAccessUpdate`), access times cannot show use. An old modification date is never enough on its own.

**What to do:**

* In Explorer, select files you know are unused and choose **Confirm inactive**. Choose **Mark as active** for files you
  use.
* Wait for the observation period to pass (14 days by default).
* IdleStow does not change this Windows setting. Changing it is your decision and happens outside IdleStow.

## Not enough free space

**Message:** *"Insufficient free space for ...: X available, Y needed plus the Z safety reserve"*

**Cause:** IdleStow keeps a safety reserve of free space on each volume (**Settings > Performance > Keep free space**,
2 GiB by default). It does not start work that would leave less free space than this.

**What to do:** free space on the volume, or choose a smaller reserve if you understand the trade-off. Settings allow
down to 256 MiB.

## Restore conflicts

**Messages:** *"A file already exists at ..."* or *"An archive already exists at ... refusing to overwrite it"*

**Cause:** IdleStow never overwrites a file. If a file already exists at the original path, the restore stops.

**What to do:** in **Recovery**, choose **Restore to another folder...** (or **Extract to another folder...** for an
archive copy). You can also rename or move the existing file first, then restore in place.

## Recovery required, Damaged or Missing

**Symptom:** a job or record shows *Recovery required*, *Review*, *Damaged* or *Missing*. The Recovery screen shows a
warning banner for records that need attention.

**Cause:** an operation was interrupted, or an archive failed verification. When IdleStow starts, it completes an
interrupted operation if the result can be fully verified, rolls it back if nothing visible changed, or flags it for
review. A damaged archive is reported, never deleted.

**What to do:** open **Recovery**, read the note on the record, and use **Verify integrity**. If an archive is damaged and
its retained original has already been removed, restore the file from a backup. See [Recovery](recovery.md#when-recovery-is-not-possible).

## The database could not be opened

**Dialog:** *"IdleStow cannot open its database"* with three choices:

* **Open data folder**: opens `%LOCALAPPDATA%\IdleStow` so you can inspect or back up the files.
* **Move the damaged database aside and start fresh**: renames the database and its side files to
  `idlestow.db.damaged-<time>`, then starts with an empty database. Nothing is deleted.
* **Exit**: closes IdleStow without changes.

The dialog says that your files are not affected. The database only holds IdleStow's index, history and settings.

**Before you start fresh:** the recovery records inside the old database stay in the renamed file, but IdleStow starts
with an empty database and will not list them. If you may need them later, keep the renamed file. Restore any archived
files you still need before you start fresh if you can. Archives and retained originals on disk are not touched.

## No recommendations appear

Recommendations need all of these:

* the files are in an authorized folder and have been scanned (press **F5**),
* the file is inactive with at least the minimum confidence (Medium by default),
* the observation period has passed (14 days by default),
* the file is at least the minimum file size (64 KiB) and saves at least the minimum (1 MiB and 10 %),
* the file is not already compressed, is not pinned or excluded, and is not an IdleStow archive.

Lowering the inactivity threshold in **Settings > Optimization** widens what counts as idle. Lowering the minimum
confidence shows more files, but with less evidence behind each one.

## Autonomous mode does not optimize anything

Autonomous work waits until all of these conditions hold:

* autonomous mode is confirmed and its policy has not changed since you confirmed it (any change needs confirmation again),
* background operation is on,
* the computer is not on battery, unless you allowed it,
* the CPU use is below the limit,
* you are idle, if *Only when I am idle* is on,
* no recovery item needs your attention,
* you have not paused IdleStow.

Autonomous mode also needs at least one folder with autonomy allowed.

## Pause and resume

The **Pause** button in the toolbar stops optimization and background work until you press **Resume**. Settings >
Automation can pause for 1 or 4 hours instead. The tray menu offers *Pause 1 hour*.

## Read-only mode

**Banner:** *"Read-only mode: IdleStow indexes and recommends but never changes a file."*

IdleStow was started with `--read-only`. Start it again without that flag to allow changes.

## IdleStow does not start: "already running"

A second copy of IdleStow asks the running copy to show its window, then exits. If no window appears, look for IdleStow
in the notification area, or end the process in Task Manager and start it again.

## Where the logs are

Logs are in:

```text
%LOCALAPPDATA%\IdleStow\logs\idlestow.log
```

* The active log rolls over at 5 MB into dated files. IdleStow keeps up to five days of history, with a 25 MB total cap.
* **Settings > Advanced > Verbose logging** raises IdleStow's own messages to debug detail. Turn it off again when you have
  finished.
* **Settings > Privacy > Clear diagnostic logs** deletes old log files. The active log stays.
* Logs may contain file paths. They never contain file contents.

When you report a problem, include the relevant log lines, and remove any paths you do not want to share.

Other locations you may need:

* Settings > Privacy > **Open data folder** opens `%LOCALAPPDATA%\IdleStow`.
* `%LOCALAPPDATA%\IdleStow\recovery\manifests` holds an independent manifest for each archive operation.
