# Known issues and limitations

Severity: Critical · High · Medium · Low. No open Critical or High data-integrity/authorization issues are known.

| ID | Severity | Area | Description | Status / workaround |
|---|---|---|---|---|
| KI-1 | Medium | Platforms | Linux and macOS adapters are implemented but **not verified** on those systems. Engine scenario tests rely on setting file creation times, which Linux filesystems do not allow; they may fail or classify differently on Linux CI. | Planned: run CI on Linux/macOS and adapt tests to a clock offset instead of creation times. |
| KI-2 | Medium | Activity | On NTFS, last-access times are only rewritten if the stored value is more than ~1 hour old, so a genuine user access within an hour after IdleStow read the file (hashing, sampling) is invisible. IdleStow ignores its own reads only within ±5 s of the read interval and re-assesses every file immediately before autonomous mutation, but this OS limitation remains. | Documented; autonomous mode additionally requires high confidence and a confirmed policy. |
| KI-3 | Resolved | Concurrency | Recovery-record operations are now also locked per record (security audit 2026-11-25). | Fixed. |
| KI-4 | Low | Accounting | Reclaimed space for archive replacements is computed from logical sizes; for originals that were sparse or already compressed this can be over-reported. | Such files are refused for archive replacement (sparse/compressed) in practice. |
| KI-5 | Low | Packaging | No MSI/EXE installer: the WiX Toolset is not installed on the build machine. Only the portable image/zip is produced. | `scripts/package-windows.ps1` builds an MSI automatically when WiX is on PATH. |
| KI-6 | Low | Security hardening | The autonomy-confirmation digest is an unkeyed SHA-256 stored in the local database; a process running as the same user could forge it (same-user attackers are out of scope). | Possible future improvement: keyed MAC with a key stored outside the database. |
| KI-7 | Low | Native compression | NTFS compression is unavailable on volumes with clusters larger than 4 KiB, ReFS, FAT/exFAT, network and read-only volumes; 128-bit ReFS file ids are not supported. | Such volumes are detected and the method is not offered. |
| KI-8 | Info | Python worker (dev) | In development mode the worker is started with the interpreter from `PATH`/`IDLESTOW_PYTHON` without `-I` isolation (bundled builds use `-s` and a `._pth` file). | Development only. |
| KI-9 | Low | UI | A few UI loads swallow errors (`ExplorerView`, onboarding mode change) and recovery "decompress" reports only the last job id when several are started (REV-1 #8, #9). | Planned clean-up. |
| KI-10 | Low | UI | Explorer *Open* launches the file's default handler without a warning, including executables inside monitored folders (REV-1 #12). | Planned: warn for executable types. |
| KI-11 | Low | App | `--smoke-test --data-dir <real data dir>` would add a sample folder to that database; a second launch initialises logging before detecting the running instance (REV-1 #13, #14). | Use a throw-away data dir for smoke tests. |
| KI-12 | Low | DB recovery | The damaged-database dialog offers "move aside and start fresh" for every open failure, including a newer schema; archive records then stay only in the renamed file and the JSON manifests (REV-1 #7). | Recovery manifests and archive-internal manifests allow manual restore (docs/user-guide/recovery.md). |
| KI-13 | Info | Release engineering | Release binaries are unsigned; CI actions are pinned by tag, not commit SHA; no SHA256SUMS file is produced. | Planned before a public release. |
