# Indexing benchmark

Hardware: AMD64 Family 25 Model 80 Stepping 0, AuthenticAMD, 12 logical CPUs, max heap 8168 MiB

OS: Windows 11 10.0

Java: 21.0.1 (Oracle Corporation)

Dataset: synthetic, 50 files per folder, 3 folder levels, sizes 0–64 KiB, metadata-only scan (no content reads), platform adapter: windows

| Files | Dirs | Initial index + analysis (s) | Files/s | Unchanged rescan (s) | Explorer page query (ms) | Search query (ms) | DB size (MiB) | Heap used after (MiB) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1000 | 20 | 0.50 | 2008 | 0.33 | 2.2 | 1.2 | 1.2 | 10 |
| 10000 | 200 | 5.01 | 1998 | 3.31 | 0.7 | 13.3 | 10.4 | 6 |
| 100000 | 2000 | 35.70 | 2801 | 38.79 | 1.8 | 114.4 | 103.6 | 6 |
