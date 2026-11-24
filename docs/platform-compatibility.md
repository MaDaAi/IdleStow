# Platform compatibility

This page states what IdleStow supports, and how well each claim has been checked. The matrix matches the
"Supported platforms" section of the [README](../README.md).

## Status labels

| Label | Meaning |
|---|---|
| **Supported and tested** | Implemented, and exercised by real runs on that platform. The evidence is in [TEST_RESULTS.md](development/TEST_RESULTS.md). |
| **Implemented but not verified** | The code exists, and parts of it may have unit tests, but it has not been run on that platform. Expect surprises. |
| **Unsupported** | Not offered on that platform. IdleStow detects this and does not offer the method. |
| **Planned** | Not implemented yet. |

## Matrix

| Feature | Windows 11 / NTFS | Windows 10 | Linux | macOS |
|---|---|---|---|---|
| Indexing, activity analysis, recommendations | Supported and tested | Implemented but not verified | Implemented but not verified | Implemented but not verified |
| Transparent native compression | Supported and tested (NTFS, cluster size 4 KiB or less) | Implemented but not verified | Unsupported (Btrfs: planned) | Unsupported (planned) |
| Archive copy and replacement, restore | Supported and tested | Implemented but not verified | Implemented but not verified | Implemented but not verified |
| Desktop UI and tray | Supported and tested | Implemented but not verified | Implemented but not verified | Implemented but not verified |
| Packaged application | Supported and tested (portable image, x64) | Implemented but not verified | Planned | Planned |

## Notes

### Windows 11 with NTFS

The supported configuration, and the one with real evidence. The package is an x64 portable image with a bundled Java
runtime and CPython. The MSI installer is not built, because WiX is not installed on the development machine
([KNOWN_ISSUES.md](development/KNOWN_ISSUES.md), KI-5).

Native compression needs all of the following. Otherwise IdleStow explains why it is unavailable:

* NTFS, not network, not read-only,
* a volume that supports file compression,
* cluster size of 4 KiB or less.

ReFS, FAT and exFAT, network and read-only volumes do not support native compression here (KI-7).

The desktop UI and the tray are marked as supported because the views are built and loaded in the smoke test and the
packaged app runs. The tray menu and long-running background scheduling have only been checked with manual launches
(see [TEST_RESULTS.md](development/TEST_RESULTS.md)).

Last-access times on NTFS are updated lazily, about once an hour, and can be turned off system-wide. IdleStow reads the
setting and reports whether access times are reliable. It never changes the setting (see
[activity model](architecture/activity-model.md)).

### Windows 10

The Windows adapter uses the same NTFS code on Windows 10, but no Windows 10 machine has been used to test it. The
[README](../README.md) and [TEST_RESULTS.md](development/TEST_RESULTS.md) list it as not verified.

### Linux

The Linux adapter is implemented but was developed on Windows and has not been verified on a Linux system (KI-1). Native
compression is unsupported. Btrfs support is planned, and the adapter says so in its messages.

Engine scenario tests set file creation times, which Linux does not allow. These tests may fail or classify differently on
Linux until they are adapted (KI-1). The project's CI matrix includes Ubuntu, but the workflows have not been run because the
repository has not been pushed.

### macOS

The macOS adapter is implemented but was developed on Windows, and only its plist generation is unit tested. Last-access
tracking is reported as unknown, and native compression is unsupported. APFS support is planned. CI includes macOS, but has
not been run.

## Keeping this page accurate

Change a cell only when there is evidence. Record the evidence in [TEST_RESULTS.md](development/TEST_RESULTS.md) and
update [KNOWN_ISSUES.md](development/KNOWN_ISSUES.md) at the same time. The README matrix and this page must agree.
