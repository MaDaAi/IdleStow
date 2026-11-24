# Safety model

Priorities, in order: data integrity, security & privacy, correctness, recoverability, UX, performance.

## Authorization

* IdleStow acts **only inside folders the user explicitly authorized** (`monitored_roots`). Nothing is
  selected by default; overlapping folders and protected locations cannot be added.
* Three modes: **Manual** (the user initiates every operation), **Ask before optimizing** (default:
  recommendations need approval), **Fully autonomous** (opt-in, per folder).
* Approvals are bound to a specific plan: a SHA-256 digest of every item's path, size, modification
  time and identity plus the method. They expire (24 h) and are single use; a changed file invalidates
  the plan. Every file is re-validated again immediately before mutation.
* Autonomy requires the mode **and** a stored digest of the exact policy the user confirmed (folders,
  methods, threshold, minimum confidence, resource limits). Any change to that policy, or a damaged
  configuration, disables autonomy until the user confirms again. Autonomy only uses reversible
  native compression — never archive replacement.
* Revoking a folder takes effect for the next file even inside a running job: the `Authorizer` re-reads
  the folder's state from the database before every mutation.

## Mandatory protections (cannot be overridden)

Operating-system directories (Windows, Program Files, ProgramData, System Volume Information,
Recycle Bin, Boot, Recovery, `%LOCALAPPDATA%`/`%APPDATA%` app storage), `AppData`/`Library`/`.config`/`.cache`,
development tooling folders (`.git`, `node_modules`, virtual environments…), game libraries, container/VM
folders, cloud-sync internals, IdleStow's own data, executables and libraries, live databases, virtual
disks, password vaults, encrypted containers, page/hibernation files. Cloud placeholders, offline files,
reparse points, links and junctions are never modified or followed.

Archive replacement additionally refuses: multiple hard links, alternate data streams (or when they
cannot be checked), system/encrypted/sparse files, cloud-synchronized folders, files on a different
volume than their monitored folder.

## Filesystem safety

* No path component may be a link/junction (`Authorizer.verifyNoLinks` compares the resolved parent with
  the lexical path under the resolved root). Indexing never follows links.
* Identity is re-checked on the **same OS handle** used for the NTFS ioctl (defeats path swaps).
* Size and modification time must equal the indexed snapshot; otherwise the file is left untouched.
* Locked files (sharing violation) are skipped.
* Outputs are created exclusively; nothing is ever overwritten (archive collisions and restore
  conflicts are reported, never resolved by overwriting).
* Free space: every operation keeps a configurable reserve (default 2 GiB).

## Integrity

* SHA-256 before and after every operation; native compression is reverted if the hash or the
  compression state does not verify, or if it saves less than one cluster.
* Archives are fully decompressed and hashed before the original is moved; re-verified before the
  retained original is purged; restores are hashed after placement.
* Recovery manifests (JSON) are written outside the database and inside each archive.
* The application distinguishes *reversible native compression*, *compressed copies*, *archived files
  with a retained original*, *archived files whose original was purged (archive is the only copy)*,
  *damaged* and *missing* recovery data. Compression is not a backup.

## Python worker and IPC

* Launched with an argument list (no shell); communicates over private stdio pipes only.
* Strict validation, 1 MiB message cap, protocol version check, bounded queue, timeouts, heartbeats,
  bounded restarts.
* The worker never derives output paths from archive contents (ZIP-slip impossible), enforces exact
  member allow-lists, rejects unsafe names and special tar members, and caps decompressed output at the
  expected size (decompression-bomb guard).

## Privacy

Fully local. No accounts, no telemetry, no network access at runtime, no LLM. Logs never contain file
contents. Users can export or clear local data.
