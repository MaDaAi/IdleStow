# ADR 0003: SQLite with WAL and synchronous=FULL behind one serialized connection

Status: Accepted (2026-11-24)

## Context

Durability of the operation journal matters more than write throughput; the app is single-user and local.

## Decision

One JDBC connection guarded by a fair lock; every write in an explicit transaction; WAL journaling with `synchronous=FULL`; versioned migrations that refuse to open a newer schema; `quick_check` on open; a damaged database is never deleted automatically.

## Consequences

Indexing batches of 500 rows keep throughput acceptable. Concurrent readers wait briefly for the lock; queries are paged.
