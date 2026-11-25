-- IdleStow schema version 2: index changes driven by measured query plans (see docs/architecture/database.md).

-- Covering partial index over live files: dashboard totals, per-category/per-root breakdowns and file counts are
-- answered from this small index instead of scanning the wide indexed_items rows.
CREATE INDEX ix_items_live_files ON indexed_items(file_key, root_id, category, logical_size, physical_size)
    WHERE is_dir=0 AND present=1;

-- Pending-recommendation lists, "largest candidates" and savings sums: ordered by savings within a status.
-- (status is the leading column, so this also replaces ix_cand_status.)
DROP INDEX IF EXISTS ix_cand_status;
CREATE INDEX ix_cand_status_savings ON optimization_candidates(status, est_savings DESC);
