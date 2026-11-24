# Threat model

## Assets

1. The user's files (content, metadata, accessibility) — highest value.
2. The user's authorization decisions (which folders, which modes).
3. Privacy of file names/paths and usage patterns.
4. Integrity of IdleStow's own records (journal, recovery manifests, history).

## Trust boundaries

| Boundary | Untrusted side | Controls |
|---|---|---|
| Filesystem contents & names | Any file inside a monitored folder (could be crafted, renamed, replaced or linked at any time) | Metadata-only indexing, no link following, protections, handle-based identity checks, snapshot (size + mtime + identity) revalidation, exclusive output creation |
| Archives being verified/restored | Archive files on disk (could be tampered with) | Member allow-list, unsafe-name rejection, special-member rejection, size cap = expected size, SHA-256 check, output path never derived from archive contents |
| Java ⇄ Python IPC | The worker process (could crash, hang, misbehave) | Private stdio pipes, 1 MiB message cap, protocol version, strict parsing, timeouts, bounded restarts; Java re-verifies results and remains the only authority for mutations |
| Settings / database | A damaged or hand-edited database | Validation with safe defaults; autonomy needs a confirmed-policy digest; newer-schema databases refused; damaged databases never deleted automatically |
| Other local processes | Programs that hold files open or modify them concurrently | Lock detection (sharing violations), revalidation immediately before mutation, verification after |

Out of scope: an attacker who already runs code as the same user (they can modify files directly),
kernel-level tampering, physical attacks.

## Threats and mitigations

| Threat | Mitigation | Tests |
|---|---|---|
| Acting outside authorized folders | `Authorizer` (fresh root state, containment, protections) before every mutation | `EngineScenariosTest.revokedRootBlocksMutation`, policy/authorizer unit tests |
| Symlink / junction escape | Scanner never descends into links; `verifyNoLinks` resolves the parent and compares with the lexical path under the resolved root; reparse files refused | platform junction tests, authorizer tests |
| Path swap between check and use (TOCTOU) | NTFS ioctl issued on a handle whose file id is verified to match the indexed identity; archive steps revalidate identity before moves | `WindowsNativeTest` wrong-key test |
| ZIP-slip / absolute paths / traversal in archives | Output path is always the exact `dest`; names validated; tests with crafted archives | `python/tests/test_archive.py` |
| Decompression bomb | Output capped at the expected size; streaming verification | `python/tests/test_archive.py` |
| Command injection | No shell anywhere; subprocesses use argument lists; Windows Run-key registration rejects quotes/control characters | platform startup tests |
| SQL injection | Parameterized SQL only; dynamic fragments come from enums | unit tests with injection strings |
| IPC abuse / resource exhaustion | Size caps, queue cap (16), timeouts, heartbeat, bounded restarts, stderr line/buffer caps | `python/tests/test_ipc.py`, WorkerClient tests |
| Data loss through crash | Journaled state machine + startup recovery; originals moved (never deleted) until a verified purge | `CrashRecoveryTest` (12 injected crash points) |
| Overwriting user data | Exclusive creation; restore conflicts reported; archive name collisions refused | `EngineScenariosTest.archiveCollisionIsRefused`, `restoreNeverOverwritesExistingFile…` |
| False "recoverable" claims | Damaged/missing archive → record marked DAMAGED/MISSING; UI explains no backup exists | `damagedArchiveIsNeverReportedAsRecoverable` |
| Silent autonomy | Explicit mode + confirmed policy digest + background enabled + per-folder flag; gates for battery, CPU, idle, recovery issues | `scenarioE_F_…` |
| Privacy leakage | No network, no telemetry, logs never contain contents | code review |
