# Indexing benchmark

Hardware: AMD64 Family 25 Model 80 Stepping 0, AuthenticAMD, 12 logical CPUs, max heap 8168 MiB

OS: Windows 11 10.0

Java: 21.0.1 (Oracle Corporation)

Dataset: synthetic, 50 files per folder, 3 folder levels, sizes 0–64 KiB, metadata-only scan (no content reads), platform adapter: windows

| Files | Dirs | Initial index + analysis (s) | Files/s | Unchanged rescan (s) | Explorer page query (ms) | Search query (ms) | DB size (MiB) | Heap used after (MiB) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1000 | 20 | 1.43 | 700 | 1.36 | 1.9 | 1.2 | 1.2 | 11 |
| 10000 | 200 | 15.50 | 645 | 14.09 | 0.8 | 13.9 | 10.3 | 6 |
| 100000 | 2000 | 156.33 | 640 | 151.74 | 1.7 | 113.1 | 103.6 | 6 |
