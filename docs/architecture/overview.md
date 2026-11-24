# IdleStow architecture

IdleStow is a **modular monolith** written in Java 21, plus one persistent **Python worker process**.
There are no services, no network ports, and no external dependencies at runtime beyond the bundled
Java runtime and bundled CPython.

```mermaid
flowchart LR
    subgraph app["app module (JavaFX)"]
        UI[Views: Overview, Explorer,<br/>Recommendations, Jobs, History,<br/>Recovery, Settings, Onboarding]
        Tray[System tray / background host]
    end
    subgraph core["core module (no UI dependencies)"]
        Engine[IdleStowEngine façade]
        Index[index: Scanner, FileWatcher]
        Act[activity: ActivityClassifier]
        Pol[policy: PolicyEngine, ProtectedRules]
        Rec[recommend: AnalysisService]
        Jobs[jobs: JobManager, OperationExecutor,<br/>Authorizer, CrashRecovery]
        DB[(db: SQLite via JDBC<br/>migrations, repositories)]
        IPC[ipc: WorkerClient, PythonWorker]
        PIF[platform interfaces]
    end
    subgraph platform["platform module"]
        Win[WindowsPlatform (JNA, NTFS)]
        Lin[LinuxPlatform]
        Mac[MacPlatform]
    end
    subgraph py["Python worker (idlestow package)"]
        W[worker: JSON lines over stdio]
        An[analysis: sampling, entropy,<br/>NTFS/zip/zstd estimates]
        Cmp[compression: zip, tar.zst]
        Ver[verification: hashing, archive<br/>verify/extract/inspect]
    end
    UI --> Engine
    Tray --> Engine
    Engine --> Index & Rec & Jobs & DB
    Rec --> Act & Pol
    Jobs --> Pol & IPC & PIF
    Index --> PIF
    PIF -.implemented by.-> Win & Lin & Mac
    IPC <-->|stdin/stdout, protocol v1| W
    W --> An & Cmp & Ver
```

## Modules

| Module | Responsibility | Key types |
|---|---|---|
| `core` | All business logic; testable without a GUI or OS-specific code | `IdleStowEngine`, `Scanner`, `ActivityClassifier`, `PolicyEngine`, `AnalysisService`, `JobManager`, `OperationExecutor`, `CrashRecovery`, `Database`, `WorkerClient` |
| `platform` | OS adapters behind `core.platform` interfaces | `WindowsPlatform` (JNA: `DeviceIoControl`, `GetCompressedFileSizeW`, `GetFileInformationByHandle`, …), `LinuxPlatform`, `MacPlatform`, `Platforms.detect()` |
| `app` | JavaFX UI, tray, single-instance coordination, background mode, launcher, logging | `Launcher`, views, onboarding |
| `python/idlestow` | Compression analysis, archives, integrity verification, benchmarks, synthetic fixtures | `idlestow.worker`, `analysis`, `compression`, `verification` |

## Key design rules

1. **Only the job engine mutates user files.** UI code never touches the filesystem except to open a
   file or folder with the OS. Every mutation goes through `OperationExecutor`, which revalidates
   authorization, identity and content immediately before acting (see `safety-model.md`).
2. **Java is the authority; Python is a tool.** The worker only reads paths it is given and creates
   exactly the output paths it is given. It cannot decide to modify anything.
3. **Business logic is independent of JavaFX and of the OS.** `PlatformServices` is injected; tests
   use `FakePlatform` (mock, OS-independent) and the platform module runs native NTFS tests.
4. **Everything durable is in SQLite** (WAL, `synchronous=FULL`, versioned migrations) plus JSON
   recovery manifests that are readable without the database.
5. **Bounded resources.** Streaming I/O everywhere, paged queries, batched writes (500 rows),
   bounded queues (worker: 16 requests; watcher: 5 000 dirty paths → reconciliation),
   a bounded job pool (1–4 threads, default 1).

## Threads

| Thread | Purpose |
|---|---|
| `idlestow-indexer` (1, min priority) | Scans, incremental updates, analysis |
| `idlestow-job-N` (1–4) | Job execution; cancellation/pausing between files and inside worker streaming |
| `idlestow-scheduler` | Periodic reconciliation scans and autonomous passes (only when background operation is enabled) |
| `idlestow-watcher` + `idlestow-watch-flush` | Filesystem events, debounced (3 s) |
| `idlestow-worker-stdout/stderr/heartbeat` | IPC reader, diagnostics drain, 30 s heartbeat |
| JavaFX application thread | UI only; engine calls are dispatched to a background executor |

## Data locations

| What | Where |
|---|---|
| Database, logs, recovery manifests | `%LOCALAPPDATA%\IdleStow` (Windows), `~/Library/Application Support/IdleStow` (macOS), `$XDG_DATA_HOME/idlestow` (Linux); override with `--data-dir` / `IDLESTOW_HOME` |
| Retained originals (archive replacement) | `<monitored folder>\.idlestow\retained\<operation-uuid>\` (same volume ⇒ atomic move, no copy) |
| Archives | Next to the original: `<name>.idlestow.zip` / `<name>.idlestow.tar.zst` |

See also: [`ipc-protocol.md`](ipc-protocol.md), [`recovery-state-machine.md`](recovery-state-machine.md),
[`activity-model.md`](activity-model.md), [`safety-model.md`](safety-model.md), [`database.md`](database.md)
and the decision records in [`../decisions/`](../decisions/).
