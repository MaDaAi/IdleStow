# Recovery guide

IdleStow is designed so that you can always get your files back **without IdleStow** if needed.
Compression is not a backup: keep regular backups of important data.

## What can be undone, and how

| Optimization | What happened | How to undo |
|---|---|---|
| **Transparent native compression** (NTFS) | The file stays at the same path and opens normally; Windows stores it compressed. | Recovery → *Decompress*, or Explorer → *Decompress*. Without IdleStow: File Explorer → Properties → Advanced → untick "Compress contents to save disk space", or `compact /u "<file>"` for that single file. |
| **Compressed copy** (ZIP / tar.zst) | A verified `<name>.idlestow.zip` (or `.idlestow.tar.zst`) was created next to the untouched original. | Nothing to undo; delete the copy from Recovery (*Delete compressed copy*) if you no longer want it. |
| **Archive replacement** | The original was **moved** to `<monitored folder>\.idlestow\retained\<operation-id>\` and replaced by a verified archive. | Recovery → *Restore* (in place) or *Restore to another folder*. |
| **Archive replacement after "Reclaim space"** | After the retention period you chose to remove the retained original; the verified archive is now the **only copy**. | Recovery → *Restore*. Without IdleStow, see below. |

A restore never overwrites an existing file. If something already exists at the original path,
choose another folder or name.

## Emergency recovery without IdleStow

Every archive is a standard file:

* `.idlestow.zip` — open with Windows File Explorer, 7-Zip, `tar -xf` (Windows 10+), or any ZIP tool.
* `.idlestow.tar.zst` — `tar --zstd -xf file.idlestow.tar.zst` (GNU tar / bsdtar with zstd), or 7-Zip ZS / `zstd -d` then `tar -xf`.

Each archive contains exactly two members: your file, and `.idlestow-manifest.json` with the original
path, size, modification time and SHA-256. Verify the extracted file with PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 "C:\path\to\extracted\file"
```

and compare the result with the `sha256` value in the manifest.

Retained originals are ordinary files in `<monitored folder>\.idlestow\retained\<operation-id>\`,
next to a `manifest.json` that records where they came from. Copy them back manually if needed.

Independent manifests for every archive operation are also stored in the IdleStow data folder:
`%LOCALAPPDATA%\IdleStow\recovery\manifests\<operation-id>.json`.

## After a crash or power loss

When IdleStow starts it checks every operation that was interrupted and:

* completes it if the result can be fully verified,
* rolls it back if nothing visible had changed (removing only IdleStow's own temporary files),
* otherwise marks it **Recovery required** and shows it in the Recovery center. Nothing ambiguous is
  deleted automatically.

## When recovery is not possible

IdleStow tells you explicitly when an archive is **damaged** (fails verification) or **missing**.
If the retained original has already been removed and the archive is damaged, IdleStow cannot recover the
file — restore it from a backup. This is why IdleStow re-verifies archives before removing retained
originals, and why the retention period exists.
