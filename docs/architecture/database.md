# Database

SQLite (xerial `sqlite-jdbc`), one file `idlestow.db` in the per-user data directory. Schema migrations
live in `core/src/main/resources/db/migration/` and are applied in order by `core/db/Database.java`
(recorded in `schema_migrations`). A database whose schema is newer than the running build is refused,
not modified.

Pragmas: `journal_mode=WAL`, `synchronous=FULL`, `foreign_keys=ON`, `busy_timeout=10000`.
Migrations: V1 initial schema; V2 query indexes (covering partial index `ix_items_live_files` over live files
for dashboard aggregates, `ix_cand_status_savings` replacing `ix_cand_status`). Dashboard aggregates in `Queries`
are cached until `Database.changeStamp()` (`total_changes()` + `data_version`) changes. Rescans only write the
bookkeeping columns of rows whose scanned metadata is unchanged.
All timestamps are UTC (`*_utc` ISO-8601 strings, `*_ms` epoch milliseconds). All SQL is parameterized.

| Table | Purpose |
|---|---|
| `settings` | Key/value settings, validated on load (`Settings.fromMap`) |
| `filesystem_volumes` | Volume capabilities: FS type, native compression support, cluster size, last-access reliability |
| `monitored_roots` | Authorized folders, revocation, autonomy/archival flags, scan generation |
| `indexed_items` | One row per file/folder: identity (`file_key`), logical/physical size, timestamps, trusted access time, attributes, optimization status, compressibility estimate (valid only for the size/mtime it was computed for) |
| `activity_evidence` | Evidence events (observed modification, opened via IdleStow, self reads, user declarations) |
| `activity_classifications` | Latest classification, confidence and explanation per item |
| `directory_aggregates` | Recursive per-folder counts and sizes (active/inactive/unknown/excluded/optimized, estimated and actual savings) |
| `exclusions`, `pinned_items`, `policies` | User rules; policies are scoped GLOBAL / ROOT / DIRECTORY / EXTENSION / FILE |
| `optimization_candidates` | Recommendations with reason, policy, confidence, warnings, auto-eligibility and file snapshot |
| `optimization_plans`, `plan_items`, `approvals` | Plans with per-item snapshots; approvals bound to a plan digest, expiring, single-use |
| `jobs`, `job_operations`, `operation_journal` | Durable jobs, per-file operations and the step journal used by crash recovery |
| `optimization_history` | User-facing audit trail (before/after allocation, estimated vs actual savings, verification, outcome) |
| `recovery_records` | What can be undone and how: native / archive copy / archive replace, archive & retained paths, SHA-256, retention, status |

Rename/move detection uses `file_key` (NTFS volume serial + 64-bit file index; Unix device + inode):
a new path whose key matches a vanished row of the same root updates that row instead of creating a new
one. Hard-linked files (link count > 1) are never treated as moves and are counted once in totals.

Reconciliation: each scan increments the root's `scan_generation`; after a **completed** scan, rows not
seen are removed (except archived originals, which are kept as not-present so they remain restorable).
A cancelled scan never removes anything.
